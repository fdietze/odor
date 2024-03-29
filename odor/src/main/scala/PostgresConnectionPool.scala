package odor

import cats.implicits._
import odor.IsolationLevel
import odor.PostgresClient
import odor.facades.pg.anon.FnCall
import odor.facades.pg.mod.CustomTypesConfig
import odor.facades.pg.mod.PoolClient
import odor.facades.pg.mod.{Client => PgClient}
import odor.facades.pgPool.mod.{Config => PgPoolConfig}
import odor.facades.pgPool.mod.{^ => PgPool}
import odor.facades.pgTypes.mod.TypeFormat
import odor.facades.pgTypes.mod.TypeId

import scala.annotation.nowarn
import scala.async.Async.async
import scala.async.Async.await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.scalajs.js

class PostgresConnectionPool[I <: IsolationLevel](
  poolConfig: PgPoolConfig[PgClient],
  val logQueryTimes: Boolean = false,
  defaultIsolationLevel: I = IsolationLevel.ServerDefault,
)(implicit
  ec: ExecutionContext,
) {
  // see comment on `PostgresConnectionPool.typesConfig`
  poolConfig.setTypes(PostgresConnectionPool.typesConfig): Unit

  private val pool = new PgPool(poolConfig)

  def acquireConnection(): Future[PoolClient] = pool.connect().toFuture

  def useConnection[R](
    code: PostgresClient {
      type TransactionIsolationLevel <: I;
    } => Future[R],
  ): Future[R] = useConnection(defaultIsolationLevel)(code)

  @nowarn("msg=unused value")
  def useConnection[IL <: IsolationLevel, R](
    isolationLevel: IL,
  )(
    code: PostgresClient {
      type TransactionIsolationLevel <: isolationLevel.type;
    } => Future[R],
  ): Future[R] = async {
    val pgClient = new PostgresClient(this, isolationLevel) {
      override type TransactionIsolationLevel <: isolationLevel.type
    }

    val codeResult = await(code(pgClient).attempt)

    await(pgClient.release())

    codeResult match {
      case Left(err)  => throw err
      case Right(res) => res
    }
  }

  def end(): Future[Unit] = pool.end().toFuture
}

object PostgresConnectionPool {
  private val typesConfig = {
    // pg-node has automatic type coercion:
    // https://node-postgres.com/features/types
    // It means, that it parses the raw types it gets from postgres into corresponding javascript types.
    // But with Skunk as a frontend, we already have these mechanics.
    //
    // Here, we're replacing all parsers with the identity function, so that we can pass on the raw data from postgres to Skunk.

    // list of all implemented parsers in pg-node:
    // https://github.com/brianc/node-pg-types/blob/8594bc6befca3523e265022f303f1376f679b5dc/lib/textParsers.js

    // Test, which uses custom type parsers:
    // https://github.com/brianc/node-postgres/blob/b1a8947738ce0af004cb926f79829bb2abc64aa6/packages/pg/test/integration/client/custom-types-tests.js

    // Internally, pg-node requests a type-parser for every returned type (oid) of a result set.
    // By letting it always return the identity function, we're overwriting all parsers at once:
    type GetTypeParserFn = js.Function2[TypeId, TypeFormat, js.Function1[String, js.Any]]
    val identityTypeParser: GetTypeParserFn = (_, _) => raw => raw
    CustomTypesConfig(identityTypeParser.asInstanceOf[FnCall])
  }

  def apply[I <: IsolationLevel](
    connectionString: String,
    maxConnections: Int,
    logQueryTimes: Boolean = false,
    defaultIsolationLevel: I = IsolationLevel.ServerDefault,
  )(implicit
    ec: ExecutionContext,
  ): PostgresConnectionPool[I] =
    new PostgresConnectionPool(
      PgPoolConfig[PgClient]()
        .setConnectionString(connectionString)
        .setMax(maxConnections.toDouble),
      logQueryTimes = logQueryTimes,
      defaultIsolationLevel = defaultIsolationLevel,
    )
  // https://node-postgres.com/api/pool
}
