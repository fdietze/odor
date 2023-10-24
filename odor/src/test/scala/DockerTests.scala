package odor

import scala.async.Async.{async, await}
import scala.annotation.nowarn

import skunk.implicits._
import skunk.codec.all._

import org.scalatest.flatspec.AsyncFlatSpec
import scala.concurrent.Future

// tests that require a running postgres instances (we use docker compose)
@nowarn("msg=unused value of type org.scalatest.Assertion")
@nowarn("msg=discarded non-Unit value of type org.scalatest.Assertion")
class DockerTests extends AsyncFlatSpec {
  implicit val ec                        = org.scalajs.macrotaskexecutor.MacrotaskExecutor
  implicit override def executionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  final val PG_CONNECTION_STRING: String = "postgresql://postgres:test3@localhost:5532"

  lazy val pool: PostgresConnectionPool =
    PostgresConnectionPool(PG_CONNECTION_STRING, maxConnections = 10)

  def assertSessionTransactionLevel(level: IsolationLevel.ReadCommitted, pgClient: PostgresClient): Future[Unit] =
    async {
      val sessionLevel: String =
        await(pgClient.querySingleRow(sql"SHOW TRANSACTION ISOLATION LEVEL".query(text)))
      assert(sessionLevel == level.postgresName.get)
    }

  def assertCurrentIsolationLevel(level: IsolationLevel.ReadCommitted, pgClient: PostgresClient): Future[Unit] = async {
    val currentLevel: String =
      await(pgClient.querySingleRow(sql"SELECT CURRENT_SETTING('TRANSACTION_ISOLATION')".query(text)))
    assert(currentLevel == level.postgresName.get)
  }

  "PostgresConnectionPool" should "successfully execute a trivial SELECT" in {
    pool.useConnection() { pgClient =>
      pgClient.tx {
        async {
          val res: Int = await(pgClient.querySingleRow(sql"SELECT 1337".query(int4)))
          assert(res == 1337)
        }
      }
    }
  }

  "PostgresConnectionPool" should "correctly set the transaction isolation level" in async {
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

  "PostgresConnectionPool" should "respect TransactionIsolationMode" in async {
    await(
      pool.useConnection(
        isolationLevel = IsolationLevel.Serializable,
        isolationMode = TransactionIsolationMode.PerSession,
      ) { pgClient =>
        assertSessionTransactionLevel(IsolationLevel.Serializable, pgClient)
      },
    )

    // need a different pool for `PerTransaction`, or the session-setting will leak.
    {
      val pool = PostgresConnectionPool(PG_CONNECTION_STRING, maxConnections = 10)
      await(
        pool.useConnection(
          isolationLevel = IsolationLevel.Serializable,
          isolationMode = TransactionIsolationMode.PerTransaction,
        ) { pgClient =>
          // @nowarn("msg=unused value of type scala\\.concurrent\\.Future\\[Unit\\]")
          val x = async {
            await(assertSessionTransactionLevel(IsolationLevel.ReadCommitted, pgClient))

            await(pgClient.tx {
              async {
                await(assertCurrentIsolationLevel(IsolationLevel.Serializable, pgClient))
              }
            })

            await(assertSessionTransactionLevel(IsolationLevel.ReadCommitted, pgClient))
          }
          x
        },
      ): Unit
    }

    succeed
  }

  // "PostgresClient" should "respect TransactionIsolationMode.PerTransaction" in async {
  //   ???
  // }
}
