package odor

import skunk._
import skunk.implicits._

sealed trait IsolationLevel {
  def postgresName: Option[String]
  def postgresNameFrag: Option[Fragment[Void]]
}

object IsolationLevel {
  sealed trait ServerDefault extends IsolationLevel

  case object ServerDefault extends ServerDefault {
    override val postgresName     = None
    override val postgresNameFrag = None
  }
  // Every isolation level provides a trait and an object extending that trait.
  // The trait is used when specifying the level as a type.
  // The object is used when specifying the level as a value.
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
