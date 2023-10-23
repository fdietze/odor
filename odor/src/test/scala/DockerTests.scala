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

  lazy val pool: PostgresConnectionPool =
    PostgresConnectionPool("postgresql://postgres:test3@localhost:5532", maxConnections = 10)

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
    def assertTransactionIsolationLevel(pgClient: PostgresClient): Future[Unit] = async {
      pgClient.transactionIsolationLevel.postgresName match {
        case Some(isolationName) =>
          val currentLevel: String =
            await(pgClient.querySingleRow(sql"SELECT CURRENT_SETTING('TRANSACTION_ISOLATION')".query(text)))
          assert(currentLevel == isolationName)
        case None => fail(s"transactionIsolationLevel '${pgClient.transactionIsolationLevel}' is not named")
      }
    }

    await(
      Future.sequence(
        for (
          isolationLevel <-
            Vector(IsolationLevel.ReadCommitted, IsolationLevel.RepeatableRead, IsolationLevel.Serializable)
        ) yield {
          pool.useConnection(isolationLevel = isolationLevel) { pgClient =>
            assertTransactionIsolationLevel(pgClient)
          }
        },
      ),
    ): Unit

    succeed
  }
}
