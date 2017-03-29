package com.davinkim.mongodb_migrations

import com.davinkim.Collections.Collections
import java.io._
import java.nio.file.Files
import play.api.libs.json._
import scala.io.Source
import scala.util.control.NonFatal

trait Evolutions extends MongoScriptExecutor with EvolutionHelperScripts with MongevLogger {
  def compareHashes: Boolean

  def updateEvolutionScript(revision: Int = 1, comment: String = "Generated", ups: String, downs: String) {

    val evolutions = getCurrentDirectory.toPath.resolve(evolutionsFilename(revision))
    Files.createDirectory(getCurrentDirectory.toPath.resolve(evolutionsDirectoryName))

    val content = Option(evolutions).filter(_.toFile.exists()).map(p => new String(Files.readAllBytes(p))).getOrElse("")

    val evolutionContent = """|// --- %s
                             |
                             |// --- !Ups
                             |%s
                             |
                             |// --- !Downs
                             |%s
                             |
                             | """.stripMargin.format(comment, ups, downs)
    if (evolutionContent != content) {
      Files.write(evolutions, evolutionContent.getBytes)
    }
  }

  def getCurrentDirectory = new File(".").getCanonicalFile

  def resolve(revision: Int) {
    execute(setAsApplied(revision, "applying_up"))
    execute(removeAllInState(revision, "applying_down"))
  }

  def checkEvolutionsState() {
    execute(unfinishedEvolutionsQuery) foreach {
      case JsArray((problem: JsObject) +: _) =>
        val revision = (problem \ "revision").as[Int]
        val state = (problem \ "state").as[String]
        val hash = (problem \ "hash").as[String].take(7)
        val script = state match {
          case "applying_up" => (problem \ "db_up").as[String]
          case _ => (problem \ "db_down").as[String]
        }
        val error = (problem \ "last_problem").as[String]

        logger.error(error)

        val humanScript = "// --- Rev:" + revision + ", " + (if (state == "applying_up") "Ups" else "Downs") + " - " + hash + "\n\n" + script

        throw InconsistentDatabase(script = humanScript, error = error, rev = revision)
      case _ =>
    }
  }

  def applyScript(script: Seq[Script]) {
    def logBefore(s: Script) = s match {
      case UpScript(e, _) =>
        val json = Json.obj(
          "revision" -> e.revision,
          "hash" -> e.hash,
          "applied_at" -> System.currentTimeMillis(),
          "db_up" -> e.db_up,
          "db_down" -> e.db_down,
          "state" -> "applying_up",
          "last_problem" -> "")
        execute(insert(json))
      case DownScript(e, _) =>
        execute(updateState(e.revision, "applying_down"))
    }

    def logAfter(s: Script) = s match {
      case UpScript(e, _) =>
        execute(updateState(e.revision, "applied"))
      case DownScript(e, _) =>
        execute(remove(e.revision))
    }

    def updateLastProblem(message: String, revision: Int) =
      execute(setLastProblem(revision, message))

    checkEvolutionsState()

    var applying = -1

    try {
      script.foreach {
        s =>
          applying = s.evolution.revision
          logBefore(s)

          val scriptType = s match {
            case UpScript(e, _) => "up"
            case DownScript(e, _) => "down"
          }

          // Execute script
          logger.debug(s"""Applying $scriptType for revision $applying """)
          execute(s.script)
          logAfter(s)
      }
    } catch {
      case NonFatal(e) =>
        updateLastProblem(e.getMessage, applying)
    }

    checkEvolutionsState()
  }

  def toHumanReadableScript(script: Seq[Script]): String = {
    val txt = script.map {
      case UpScript(ev, js) => "// --- Rev:" + ev.revision + ", Ups - " + ev.hash.take(7) + "\n" + js + "\n"
      case DownScript(ev, js) => "// --- Rev:" + ev.revision + ", Downs - " + ev.hash.take(7) + "\n" + js + "\n"
    }.mkString("\n")

    val hasDownWarning =
      "// !!! WARNING! This script contains DOWNS evolutions that are likely destructives\n\n"

    if (script.exists(_.isInstanceOf[DownScript])) hasDownWarning + txt else txt
  }

  def evolutionScript(path: File): Seq[Product with Serializable with Script] = {
    val application = applicationEvolutions(path)
    logger.debug("application evolutions: " + application.map(_.revision).mkString(" "))

    Option(application).filterNot(_.isEmpty).map {
      case `application` =>
        val database = databaseEvolutions()
        logger.debug("database evolutions: " + database.map(_.revision).mkString(" "))

        val (nonConflictingDowns, dRest) = database.span(e => !application.headOption.exists(e.revision <= _.revision))
        val (nonConflictingUps, uRest) = application.span(e => !database.headOption.exists(_.revision >= e.revision))

        val (conflictingDowns, conflictingUps) = conflicts(dRest, uRest)

        val ups = (nonConflictingUps ++ conflictingUps).reverse.map(e => UpScript(e, e.db_up))
        val downs = (nonConflictingDowns ++ conflictingDowns).map(e => DownScript(e, e.db_down))
        logger.debug("Up scripts: " + ups.map(_.evolution.revision).mkString(" "))
        logger.debug("Down scripts: " + downs.map(_.evolution.revision).mkString(" "))

        downs ++ ups
    }.getOrElse(Nil)
  }

  def conflicts(downRest: Seq[Evolution], upRest: Seq[Evolution]) = downRest.zip(upRest).reverse.dropWhile {
    case (down, up) => (!compareHashes) || (down.hash == up.hash)
  }.reverse.unzip

  def databaseEvolutions(): Seq[Evolution] = {

    checkEvolutionsState()

    execute(allEvolutionsQuery).map {
      value: JsValue =>

        value.validate(Reads.list[Evolution]) match {
          case JsSuccess(v, _) => v
          case JsError(error) => throw new Exception(s"Couldn't parse elements of evolutions collection. Error: $error")
        }
    } getOrElse Nil
  }

  private val evolutionsDirectoryName = "evolutions/"

  private def evolutionsFilename(revision: Int): String = evolutionsDirectoryName + revision + ".js"

  private def evolutionsResourceName(revision: Int): String = s"evolutions/$revision.js"

  private def gracefulFileInputStream(filePath: String): FileInputStream = {
    try {
      new FileInputStream(filePath)
    } catch {
      case e: FileNotFoundException => null
    }
  }

  def applicationEvolutions(path: File): Seq[Evolution] = {

    val upsMarker = """^//.*!Ups.*$""".r
    val downsMarker = """^//.*!Downs.*$""".r

    val UPS = "UPS"
    val DOWNS = "DOWNS"
    val UNKNOWN = "UNKNOWN"

    val mapUpsAndDowns: PartialFunction[String, String] = {
      case upsMarker() => UPS
      case downsMarker() => DOWNS
      case _ => UNKNOWN
    }

    val isMarker: PartialFunction[String, Boolean] = {
      case upsMarker() => true
      case downsMarker() => true
      case _ => false
    }

    Collections.unfoldLeft(1) {
      revision =>
        Option(new File(path, evolutionsFilename(revision))).filter(_.exists).map(new FileInputStream(_)).orElse {
          Option(gracefulFileInputStream(evolutionsResourceName(revision)))
        }.map {
          stream =>
            (revision + 1, (revision, Source.fromInputStream(stream)("UTF-8").mkString))
        }
    }.sortBy(_._1).map {
      case (revision, script) =>

        val parsed = Collections.unfoldLeft(("", script.split('\n').toList.map(_.trim))) {
          case (_, Nil) => None
          case (context, lines) =>
            val (some, next) = lines.span(l => !isMarker(l))
            Some((next.headOption.map(c => (mapUpsAndDowns(c), next.tail)).getOrElse("" -> Nil),
              context -> some.mkString("\n")))
        }.reverse.drop(1).groupBy(i => i._1).mapValues {
          _.map(_._2).mkString("\n").trim
        }

        Evolution(
          revision,
          parsed.getOrElse(UPS, ""),
          parsed.getOrElse(DOWNS, "")
        )
    }.reverse

  }

}