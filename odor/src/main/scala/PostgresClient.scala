package odor

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.{global => unsafeIORuntimeGlobal}
import cats.implicits._
import odor.IsolationLevel
import odor.PostgresConnectionPool
import odor.facades.pg.mod.PoolClient
import odor.facades.pg.mod.QueryArrayConfig
import odor.facades.pg.mod.QueryResult
import skunk._
import skunk.implicits._

import scala.annotation.nowarn
import scala.async.Async.async
import scala.async.Async.await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.|
import scala.util.Failure
import scala.util.Success

class PostgresClient(
  val pool: PostgresConnectionPool[?],
  val transactionIsolationLevel: IsolationLevel,
)(implicit
  ec: ExecutionContext,
) {

  private var pgClientIsInitialized = false
  private var pgClientIsReleased    = false

  type TransactionIsolationLevel <: IsolationLevel

  @nowarn("msg=unused value of type odor\\.facades\\.pg\\.mod\\.PoolClient")
  private lazy val connection: Future[PoolClient] = {
    if (pgClientIsReleased) Future.failed(new IllegalStateException("PostgresClient already released"))
    else {
      pgClientIsInitialized = true
      async {
        val poolClient = await(pool.acquireConnection())
        poolClient.on("error", (err: Any) => println(s"Postgres connection error: $err"))
        poolClient
      }
    }
  }

  def release(): Future[Unit] = if (!pgClientIsReleased) {
    pgClientIsReleased = true
    if (pgClientIsInitialized) connection.map(_.release())
    else Future.successful(())
  } else Future.successful(())

  val transactionSemaphore: Future[Semaphore[IO]] = Semaphore[IO](1).unsafeToFuture()

  def command[PARAMS](
    command: Command[PARAMS],
    params: PARAMS = Void,
  ): Future[Unit] = async {
    val conn           = await(connection)
    val startTimeNanos = nowNano()
    val resultOrArray = await(
      conn
        .query(
          command.sql,
          command.encoder.encode(params).map(_.orNull).toJSArray,
        )
        .toFuture,
    ).asInstanceOf[
      QueryResult[Nothing] | js.Array[QueryResult[Nothing]],
    ] // query returns either a single result or an array of results (the typescript facade and docs are wrong)
    if (pool.logQueryTimes) {
      val durationNanos  = nowNano() - startTimeNanos
      val durationMillis = durationNanos / 1000000
      if (resultOrArray.isInstanceOf[js.Array[_]]) {
        val resultArray    = resultOrArray.asInstanceOf[js.Array[QueryResult[Any]]]
        val statementCount = resultArray.length
        println(
          f"[${durationMillis}%4dms] [${statementCount}%4d statements] ${command.sql.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ").take(60)}",
        )
      } else {
        val result = resultOrArray.asInstanceOf[QueryResult[Any]]
        val affectedRows =
          result
            .asInstanceOf[js.Dynamic] // the typescript types are wrong for `rowCount`
            .rowCount
            .asInstanceOf[js.UndefOr[Double]]
            .toOption
            .map(_.toInt)
            .getOrElse(result.rows.length)
        println(
          f"[${durationMillis}%4dms] [${affectedRows}%4d rows] ${command.sql.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ").take(60)}",
        )
      }

    }
    ()
  }

  private def nowNano() = System.nanoTime()

  @nowarn("msg=unused value of type scala\\.collection\\.immutable\\.Vector\\[ROW\\]")
  def query[PARAMS, ROW](
    query: Query[PARAMS, ROW],
    params: PARAMS = Void,
  ): Future[Vector[ROW]] = async {
    val startTimeNanos = nowNano()
    val result = await(
      await(connection)
        .query[js.Array[js.Any], js.Array[js.Any]](
          QueryArrayConfig[js.Array[js.Any]](query.sql),
          query.encoder.encode(params).map(_.orNull.asInstanceOf[js.Any]).toJSArray,
        )
        .toFuture,
    ) // TODO: if multiple select statements are sent, this is .asInstanceOf[QueryResult[Nothing] | js.Array[QueryResult[Nothing]]]
    val returnedRows = result.rows.view.map { row =>
      query.decoder.decode(
        0,
        row.view.map { any =>
          // The facade has an any type, because pg-node officially decodes values to native javascript types.
          // We have this feature disabled and assume it's a String.
          if (any == null) None
          else Option(any.toString)
        }.toList,
      ) match {
        case Left(err)         => throw new Exception(err.message)
        case Right(decodedRow) => decodedRow
      }
    }.toVector
    if (pool.logQueryTimes) {
      val durationNanos  = nowNano() - startTimeNanos
      val durationMillis = durationNanos / 1000000
      println(
        f"[${durationMillis}%4dms] [${returnedRows.length}%4d rows] ${query.sql.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ").take(60)}",
      )
    }
    returnedRows
  }

  @nowarn("msg=unused value of type ROW")
  def querySingleRow[PARAMS, ROW](queryFragment: Query[PARAMS, ROW], params: PARAMS = Void): Future[ROW] = async {
    val rows = await(query[PARAMS, ROW](queryFragment, params))
    if (rows.isEmpty) throw new RuntimeException("Requested single row, but got no rows.")
    rows.head
  }

  val tx = new PostgresClient.Transaction(
    transactionSemaphore,
    command[Void](_),
    isolationLevel = transactionIsolationLevel match {
      case IsolationLevel.ServerDefault              => None
      // This match is exhaustive, because all other levels are subtypes of `ReadCommitted`
      case level: IsolationLevel.ReadCommitted => Some(level)
    },
  )

}

object PostgresClient {
  class Transaction(
    transactionSemaphore: Future[Semaphore[IO]],
    command: Command[Void] => Future[Unit],
    isolationLevel: Option[IsolationLevel.ReadCommitted],
  )(implicit ec: ExecutionContext) {

    private var recursion = 0

    @nowarn("msg=unused value of type T")
    def apply[T](code: => Future[T]): Future[T] = async {
      val semaphore = await(transactionSemaphore)

      if (recursion == 0) {
        await(semaphore.acquire.unsafeToFuture()) // wait until other transaction has finished
        val isolationFrag = isolationLevel
          .flatMap(_.postgresNameFrag)
          .map(f => sql"; SET TRANSACTION ISOLATION LEVEL $f")
          .getOrElse(Fragment.empty)
        await(command(sql"BEGIN$isolationFrag".command).attempt) match {
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
