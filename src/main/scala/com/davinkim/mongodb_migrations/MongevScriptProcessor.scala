package com.davinkim.mongodb_migrations

import java.io._
import play.api.libs.json._

class MongevScriptProcessor(mongoCmdString: String,
                            enabled: Boolean,
                            applyDownEvolutions: Boolean,
                            compareHashesBool: Boolean,
                            applyProdEvolutions: Boolean,
                            useLocks: Boolean,
                            evolutionsPath: String) extends Evolutions with MongevLogger {

  lazy val compareHashes = compareHashesBool
  lazy val mongoCmd = mongoCmdString

  def onStart() {
    withLock {
      val script = evolutionScript(new File(evolutionsPath))
      /**
        * TODO: Not sure what this following line is used for just yet, but this will likely come into play
        * when performing down evolutions.
        */
      //      val hasDown = script.exists(_.isInstanceOf[DownScript])

      if (script.nonEmpty) {
        applyScript(script)
      }
    }
  }

  def withLock(block: => Unit) {

    def unlock() = execute(releaseLock)

    if (useLocks) {
      execute(acquireLock) match {
        case Some(o: JsObject) =>
          val lock = (o \ "value" \ "lock").as[Int]
          if (lock == 1) {
            // everything is fine, we acquired the lock
            try {
              block
            } finally {
              unlock()
            }
          } else {
            // someone else holds the lock, we try again later
            logger.error(s"The db is already locked by another process." +
              " Wait for it to finish or delete the collection '$lockDBName'.")
            unlock()
          }
        case _ =>
          logger.error("Failed to acquire lock.")
      }
    } else
      block
  }

}