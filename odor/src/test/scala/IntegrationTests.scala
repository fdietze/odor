package odor

import scala.async.Async.{async, await}
import scala.annotation.nowarn

import skunk.implicits._
import skunk.codec.all._

import org.scalatest.flatspec.AsyncFlatSpec
import scala.concurrent.Future
import scala.annotation.unused

// tests that require a running postgres instances (we use docker compose)
@nowarn("msg=unused value of type org.scalatest.Assertion")
@nowarn("msg=unused value of type org.scalatest.Succeeded.type")
@nowarn("msg=discarded non-Unit value of type org.scalatest.Assertion")
class IntegrationTests extends AsyncFlatSpec {
  implicit val ec                        = org.scalajs.macrotaskexecutor.MacrotaskExecutor
  implicit override def executionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  // must be the same as in `docker-compose.yml`
  final val PG_CONNECTION_STRING: String = "postgresql://postgres:test3@localhost:5532"

  lazy val pool: PostgresConnectionPool[?] =
    PostgresConnectionPool(PG_CONNECTION_STRING, maxConnections = 10)

  // helper function that only compiles if the given `PostgresClient` has at least isolation level
  // `RepeatableRead`.
  // `unused` because it's used in compile-tests
  @unused
  def repeatableRead(
    @unused p: PostgresClient { type TransactionIsolationLevel <: IsolationLevel.RepeatableRead },
  ): Future[Unit] = Future.unit

  def assertCurrentIsolationLevel(level: IsolationLevel.ReadCommitted, pgClient: PostgresClient): Future[Unit] =
    async {
      val currentLevel: String =
        await(pgClient.tx {
          pgClient.querySingleRow(sql"SHOW TRANSACTION ISOLATION LEVEL".query(text))
        })
      assert(currentLevel == level.postgresName.value)
    }

  "PostgresConnectionPool" should "successfully execute a trivial SELECT" in {
    pool.useConnection { pgClient =>
      pgClient.tx {
        async {
          val res: Int = await(pgClient.querySingleRow(sql"SELECT 1337".query(int4)))
          assert(res == 1337)
        }
      }
    }
  }

  "PostgresClient" should "correctly set the transaction isolation level" in async {
    await(
      Future.sequence(
        for (
          isolationLevel <-
            Vector(IsolationLevel.ReadCommitted, IsolationLevel.RepeatableRead, IsolationLevel.Serializable)
        ) yield {
          pool.useConnection(isolationLevel = isolationLevel) { pgClient =>
            assertCurrentIsolationLevel(isolationLevel, pgClient)
          }
        },
      ),
    ): Unit

    succeed
  }

  "PostgresConnectionPool" should "set a default isolation level" in async {
    val defaultLevel = IsolationLevel.RepeatableRead

    val pool: PostgresConnectionPool[IsolationLevel.RepeatableRead] = PostgresConnectionPool(
      PG_CONNECTION_STRING,
      maxConnections = 2,
      defaultIsolationLevel = defaultLevel,
    )

    await(pool.useConnection { pgClient =>
      assertCurrentIsolationLevel(defaultLevel, pgClient)
    })

    val overrideLevel = IsolationLevel.Serializable

    await(pool.useConnection(isolationLevel = overrideLevel) { pgClient =>
      assertCurrentIsolationLevel(overrideLevel, pgClient)
    })

    import org.scalatest.matchers.should.Matchers._

    @unused
    def serializable(
      @unused p: PostgresClient { type TransactionIsolationLevel <: IsolationLevel.Serializable },
    ): Future[Unit] = Future.unit

    """
    pool.useConnection { pgClient =>
      serializable(pgClient)
    }
    """ shouldNot typeCheck

    """
    pool.useConnection(isolationLevel = overrideLevel) { pgClient =>
      serializable(pgClient)
    }
    """ should compile
  }

  "PostgresConnectionPool" should "propagate the default isolation level at compile time" in async {
    import org.scalatest.matchers.should.Matchers._

    // connection pool that has a default isolation level of `RepeatableRead`
    @unused
    val pool: PostgresConnectionPool[IsolationLevel.RepeatableRead] = PostgresConnectionPool(
      PG_CONNECTION_STRING,
      maxConnections = 2,
      defaultIsolationLevel = IsolationLevel.RepeatableRead,
    )

    """
    pool.useConnection { pgClient =>
      repeatableRead(pgClient)
    }
    """ should compile
  }
}
