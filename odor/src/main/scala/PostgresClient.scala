package odor

import skunk._

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.annotation.nowarn
import facades.pg.mod.ClientConfig
import facades.pg.mod.QueryArrayConfig
import facades.pg.mod.{Client => PgClient}
import cats.effect.std.Semaphore
import cats.effect.IO
import cats.implicits._
import cats.effect.unsafe.implicits.{global => unsafeIORuntimeGlobal}

import scala.scalajs.js.JSConverters._
import scala.util.{Failure, Success}
import skunk.implicits._

@js.native
@JSImport("pg-connection-string", JSImport.Namespace)
object PgConnectionString extends js.Object {
  def parse(arg: String): ClientConfig = js.native
}

class PostgresClient(connectionString: String)(implicit ec: ExecutionContext) {

  private val client: PgClient              = new PgClient(PgConnectionString.parse(connectionString))
  private lazy val connection: Future[Unit] = client.connect().toFuture

  val transactionSemaphore: Future[Semaphore[IO]] = Semaphore[IO](1).unsafeToFuture()

  def command[PARAMS](
    command: Command[PARAMS],
    params: PARAMS = Void,
  ): Future[Unit] = async {
    await(connection)
    await(
      client
        .query(
          command.sql,
          command.encoder.encode(params).map(_.orNull).toJSArray,
        )
        .toFuture,
    )
    ()
  }

  def query[PARAMS, ROW](
    query: Query[PARAMS, ROW],
    params: PARAMS = Void,
  ): Future[Vector[ROW]] = async {

    await(connection) // wait until connection is ready
    val result = await(
      client
        .query[js.Array[js.Any], js.Array[js.Any]](
          QueryArrayConfig[js.Array[js.Any]](query.sql),
          query.encoder.encode(params).map(_.orNull.asInstanceOf[js.Any]).toJSArray,
        )
        .toFuture,
    )
    result.rows.view.map { row =>
      query.decoder.decode(
        0,
        row.view
          .map(any =>
            Option(any: Any).map {
              // pg-node automatically parses types
              // https://node-postgres.com/features/types
              // But we don't want that, since skunk has its own decoders,
              // which work with strings
              //
              // TODO: tests for these data types
              case bool: Boolean => if (bool) "t" else "f"
              case date: js.Date =>
                // toString on js.Date localizes the date. `toISOString`
                // also adds time information that doesn't exist in the original
                // `Date` object in the database. This overhead gets cut away by
                // calling `substring`.
                date.toISOString().substring(0, 10)
              case jsObject: js.Object => js.JSON.stringify(jsObject)
              case other               => other.toString
            },
          )
          .toList,
      ) match {
        case Left(err)         => throw new Exception(err.message)
        case Right(decodedRow) => decodedRow
      }
    }.toVector
  }

  def querySingleRow[PARAMS, ROW](queryFragment: Query[PARAMS, ROW], params: PARAMS = Void): Future[ROW] = async {
    val rows = await(query[PARAMS, ROW](queryFragment, params))
    if (rows.isEmpty) throw new RuntimeException("Requested single row, but got no rows.")
    rows.head
  }

  def newTransaction() = new PostgresClient.Transaction(connection, transactionSemaphore, command[Void](_))

}

object PostgresClient {
  class Transaction(
    connection: Future[Unit],
    transactionSemaphore: Future[Semaphore[IO]],
    command: Command[Void] => Future[Unit],
  )(implicit ec: ExecutionContext) {

    private var recursion = 0

    def apply[T](code: => Future[T]): Future[T] = async {
      await(connection) // wait until connection is ready
      val semaphore = await(transactionSemaphore)

      if (recursion == 0) {
        await(semaphore.acquire.unsafeToFuture()) // wait until other transaction has finished
        await(command(sql"BEGIN".command).attempt) match {
          case Left(err) =>
            await(semaphore.release.unsafeToFuture())
            throw err
          case _ =>
        } // begin transaction
      }

      recursion += 1

      await(code.transformWith { codeResult =>
        recursion -= 1
        codeResult match {
          case Success(result) =>
            async {
              if (recursion == 0) {
                val committed = await(command(sql"COMMIT".command).attempt)
                await(semaphore.release.unsafeToFuture())
                committed match {
                  case Left(err) => throw err
                  case _         =>
                }
              }
              result
            }
          case Failure(exception) =>
            async {
              if (recursion == 0) {
                println(s"Transaction failed. Rolling back.")
                val rolledBack = await(command(sql"ROLLBACK".command).attempt)
                await(semaphore.release.unsafeToFuture())
                rolledBack match {
                  case Right(_) => throw exception
                  case Left(err) =>
                    val finalException = new Exception("ROLLBACK FAILED")
                    finalException.addSuppressed(err)
                    finalException.addSuppressed(exception)

                    throw finalException
                }
              } else {
                // in nested transaction,
                // therefore just forward exception
                throw exception
              }
            }
        }
      })

    }

  }
}
