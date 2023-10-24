package odor

import skunk._
import skunk.implicits._

sealed trait IsolationLevel {
  def postgresName: Option[String]
  def postgresNameFrag: Option[Fragment[Void]]
}

object IsolationLevel {
  sealed trait Default extends IsolationLevel

  case object Default extends IsolationLevel {
    override val postgresName     = None
    override val postgresNameFrag = None
  }

  sealed trait ReadCommitted extends IsolationLevel {
    override def postgresName: Some[String]
    override def postgresNameFrag: Some[Fragment[Void]]
  }
  sealed trait RepeatableRead extends ReadCommitted
  sealed trait Serializable   extends RepeatableRead

  case object ReadCommitted extends ReadCommitted {
    override val postgresName     = Some("read committed")
    override val postgresNameFrag = Some(const"read committed")
  }

  case object RepeatableRead extends RepeatableRead {
    override val postgresName     = Some("repeatable read")
    override val postgresNameFrag = Some(const"repeatable read")
  }

  case object Serializable extends Serializable {
    override val postgresName     = Some("serializable")
    override val postgresNameFrag = Some(const"serializable")
  }
}

/** When to set the specified `IsolationLevel`.
  *
  * Some postgres-proxies might not support setting the isolation level per-session, in which case `PerTransaction` can
  * be chosen instead. This which will not set a session-wide isolation level, but instead set the isolation level at
  * the start of each transaction.
  */
sealed trait TransactionIsolationMode

object TransactionIsolationMode {
  case object PerSession     extends TransactionIsolationMode
  case object PerTransaction extends TransactionIsolationMode
}
