package com.davinkim.mongodb_migrations

import org.apache.commons.codec.digest.DigestUtils
import play.api.libs.json._

private[mongodb_migrations] case class Evolution(revision: Int, db_up: String = "", db_down: String = "") {
  val hash = DigestUtils.sha1Hex(db_down.trim + db_up.trim)
}

private[mongodb_migrations] object Evolution {
  import scala.language.implicitConversions

  implicit val evolutionReads = Json.reads[Evolution]
}
