package odor

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.implicits._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AsyncFlatSpec

import scala.annotation.nowarn
import scala.async.Async.{async, await}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}
import scala.scalajs.js.timers._
import scala.util.Success
import scala.annotation.unused

@nowarn("msg=dead code") // because throw in async marco
@nowarn("msg=unused value")
class PgClientSpecUnitTests extends AsyncFlatSpec with BeforeAndAfterEach {
  implicit val ec                        = org.scalajs.macrotaskexecutor.MacrotaskExecutor
  implicit override def executionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  val buffer    = ArrayBuffer[String]()
  val semaphore = Semaphore[IO](1).unsafeToFuture()

  val tx = new PostgresClient.Transaction(
    transactionSemaphore = semaphore,
    command = c => Future.successful(buffer += c.sql),
    isolationLevel = None,
  )

  def delay(delayMs: Long): Future[Unit] = {
    val promise = Promise[Unit]()
    setTimeout(delayMs.toDouble) {
      promise.complete(Success(()))
    }
    promise.future
  }

  override def beforeEach(): Unit = {
    buffer.clear()
  }

  "Transaction" should "embed simple sql in BEGIN and COMMIT tags" in async {
    await {
      tx {
        async { buffer += "middle" }
      }
    }

    assert(buffer === ArrayBuffer("BEGIN", "middle", "COMMIT"))
  }

  "Transaction" should "linearize interleaving" in async {
    // two pieces of code, which would normally interleave due to a delay.
    // the transaction must linearize these code paths.

    val tx1 = tx {
      async {
        buffer += "1"
        await(delay(1000))
        buffer += "3"
      }
    }
    val tx2 = tx {
      async {
        buffer += "2"
        await(delay(1000))
        buffer += "4"
      }
    }

    await(tx1)
    await(tx2)

    assert(buffer === ArrayBuffer("BEGIN", "1", "3", "COMMIT", "BEGIN", "2", "4", "COMMIT"))
  }

  "Transaction" should "flatten nesting" in async {
    await {
      tx {
        async {
          buffer += "1"
          await(tx {
            async {
              buffer += "middle"
            }
          })
          buffer += "2"
        }
      }
    }

    assert(buffer === ArrayBuffer("BEGIN", "1", "middle", "2", "COMMIT"))
  }

  "Transaction" should "simple rollback" in async {
    val peng = new Exception("peng")

    assert(await(tx {
      async {
        buffer += "middle"
        throw peng
      }
    }.attempt) == Left(peng))

    assert(buffer === ArrayBuffer("BEGIN", "middle", "ROLLBACK"))
  }

  "Transaction" should "rollback flatten nesting" in async {
    val peng = new Exception("peng")

    val expected = Array(
      ArrayBuffer("BEGIN", "1", "2", "3", "4", "5", "6", "COMMIT"),
      ArrayBuffer("BEGIN", "1", "ROLLBACK"),
      ArrayBuffer("BEGIN", "1", "2", "3", "ROLLBACK"),
      ArrayBuffer("BEGIN", "1", "2", "3", "4", "5", "ROLLBACK"),
    )

    var state = 0
    while (state <= 3) {
      buffer.clear()
      await {
        tx {
          async {
            buffer += "1"
            if (state == 1) throw peng
            buffer += "2"
            await(tx {
              async {
                buffer += "3"
                if (state == 2) throw peng
                buffer += "4"
              }
            })
            buffer += "5"
            if (state == 3) throw peng
            buffer += "6"
          }
        }.attempt
      }
      assert(buffer === expected(state))
      assert(await(await(semaphore).available.unsafeToFuture()) == 1)

      state += 1
    }

    assert(true) // because while returns unit

  }

  "Transaction" should "simple commit failed" in async {
    // we're testing if after a failed rollback, the semaphore is available again

    val connFail = new Exception("broken connection")

    val failedSql = Array("BEGIN", "COMMIT")
    val expected = Array(
      ArrayBuffer(),
      ArrayBuffer("BEGIN", "middle"),
    )

    var i = 0
    while (i < 2) {
      val tx = new PostgresClient.Transaction(
        transactionSemaphore = semaphore,
        command = c => if (c.sql == failedSql(i)) Future.failed(connFail) else Future.successful(buffer += c.sql),
        isolationLevel = None,
      )

      assert(await(tx {
        async {
          buffer += "middle"
        }
      }.attempt) == Left(connFail))
      assert(buffer === expected(i))

      // semaphore is released, ready to be acquired again
      assert(await(await(semaphore).available.unsafeToFuture()) == 1)

      i += 1
    }

    assert(true) // because while returns unit

  }

  "Transaction" should "simple rollback failed" in async {
    // we're testing if after a failed rollback, the semaphore is available again

    val peng      = new Exception("peng")
    val connFail  = new Exception("broken connection")
    val semaphore = Semaphore[IO](1).unsafeToFuture()

    val tx = new PostgresClient.Transaction(
      transactionSemaphore = semaphore,
      command = c => if (c.sql == "ROLLBACK") Future.failed(connFail) else Future.successful(buffer += c.sql),
      isolationLevel = None,
    )

    assert(await(tx {
      async {
        buffer += "middle"
        throw peng
      }
    }.attempt).left.toOption.get.getMessage == "ROLLBACK FAILED")
    assert(buffer === ArrayBuffer("BEGIN", "middle"))

    // semaphore is released, ready to be acquired again
    assert(await(await(semaphore).available.unsafeToFuture()) == 1)

  }

  "PostgresClientPool" should "support only compatible isolation levels" in async {
    val p = PostgresConnectionPool("", 1)

    def readCommitted(
      @unused p: PostgresClient,
    ): Future[Unit] = Future.unit

    def repeatableRead(
      @unused p: PostgresClient { type TransactionIsolationLevel <: IsolationLevel.RepeatableRead },
    ): Future[Unit] = Future.unit

    def serializable(
      @unused p: PostgresClient { type TransactionIsolationLevel <: IsolationLevel.Serializable },
    ): Future[Unit] = Future.unit

    p.useConnection(isolationLevel = IsolationLevel.Serializable) { pgClient =>
      readCommitted(pgClient)
      repeatableRead(pgClient)
      serializable(pgClient)

      Future.unit
    }

    import org.scalatest.matchers.should.Matchers._

    """
    p.useConnection { pgClient =>
      repeatableRead(pgClient)
    }
    """ shouldNot typeCheck

    """
    p.useConnection { pgClient =>
      serializable(pgClient)
    }
    """ shouldNot typeCheck

    """
    p.useConnection { pgClient =>
      readCommitted(pgClient)
    }
    """ should compile
  }
}
