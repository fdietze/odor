package odor

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.{global => unsafeIORuntimeGlobal}
import cats.implicits._
import odor.facades.pg.mod.{Client => PgClient, PoolClient, QueryArrayConfig}
import odor.facades.pgPool.mod.{^ => PgPool, Config => PgPoolConfig}
import skunk._
import skunk.implicits._

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success}
import scala.annotation.nowarn

@nowarn("msg=never used")
@nowarn("msg=dead code")
@js.native
@JSImport("pg-types", JSImport.Namespace)
object PgTypes extends js.Object {
  def setTypeParser(oid: Int, format: String, parseFn: js.Function1[String, js.Any]): Unit = js.native
  var getTypeParser: js.Function2[Int, String, js.Function1[String, js.Any]]               = js.native
}

object DisableAutomaticTypeParsing {
  // pg-node has automatic type coercion:
  // https://node-postgres.com/features/types
  // It means, that it parses the raw types it gets from postgres into corresponding javascript types.
  // But with Skunk as a frontend, we already have these mechanics.
  //
  // Here, we're replacing all parsers with the identity function, so that we can pass on the raw data from postgres to Skunk.

  // list of all implemented parsers in pg-node:
  // https://github.com/brianc/node-pg-types/blob/8594bc6befca3523e265022f303f1376f679b5dc/lib/textParsers.js

  // Official way to overwrite a single parser:
  // https://github.com/brianc/node-postgres/blob/master/packages/pg/test/integration/client/huge-numeric-tests.js
  // PgTypes.setTypeParser(16, "text", x => x) // 16 = OID of BOOL

  // Internally, pg-node requests a type-parser for every returned type (oid) of a result set.
  // By letting it always return the identity function, we're overwriting all parsers at once:
  PgTypes.getTypeParser = { (_, _) => raw => raw }
}

class PostgresConnectionPool(connectionString: String, maxConnections: Int)(implicit ec: ExecutionContext) {
  DisableAutomaticTypeParsing

  // https://node-postgres.com/api/pool
  private val poolConfig = PgPoolConfig[PgClient]()
    .setConnectionString(connectionString)
    .setMax(maxConnections.toDouble)

  private val pool = new PgPool(poolConfig)

  def acquireConnection(): Future[PoolClient] = pool.connect().toFuture

  def useConnection[R](code: PostgresClient => Future[R]): Future[R] = async {
    val poolClient = await(acquireConnection())
    poolClient.on("error", (err: Any) => println(s"Postgres connection error: $err"))
    val pgClient   = new PostgresClient(this, poolClient)
    val codeResult = await(code(pgClient).attempt)
    poolClient.release()
    codeResult match {
      case Left(err)  => throw err
      case Right(res) => res
    }
  }
}

class PostgresClient(val pool: PostgresConnectionPool, connection: PoolClient)(implicit ec: ExecutionContext) {

  val transactionSemaphore: Future[Semaphore[IO]] = Semaphore[IO](1).unsafeToFuture()

  def command[PARAMS](
    command: Command[PARAMS],
    params: PARAMS = Void,
  ): Future[Unit] = async {
    await(
      connection
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
    val result = await(
      connection
        .query[js.Array[js.Any], js.Array[js.Any]](
          QueryArrayConfig[js.Array[js.Any]](query.sql),
          query.encoder.encode(params).map(_.orNull.asInstanceOf[js.Any]).toJSArray,
        )
        .toFuture,
    )
    result.rows.view.map { row =>
      query.decoder.decode(
        0,
        row.view.map { any =>
          // The facade has an any type, because pg-node officially decodes values to native javascript types.
          // We have this feature disabled and assume it's a String.
          Option(any.asInstanceOf[String])
        }.toList,
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

  val tx = new PostgresClient.Transaction(transactionSemaphore, command[Void](_))

}

object PostgresClient {
  class Transaction(
    transactionSemaphore: Future[Semaphore[IO]],
    command: Command[Void] => Future[Unit],
  )(implicit ec: ExecutionContext) {

    private var recursion = 0

    def apply[T](code: => Future[T]): Future[T] = async {
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
