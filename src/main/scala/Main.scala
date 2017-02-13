package com.davinkim.mongodb_migrations

import java.io._

import play.api._
import play.api.libs.Codecs._
import play.api.libs.Collections

import scala.io.Source
import scala.util.control.NonFatal
import play.core.HandleWebCommandSupport
import play.api.libs.json._
import play.api.libs.Files.TemporaryFile
import org.slf4j.LoggerFactory
import java.nio.file.Files
import javax.inject.Inject

import reactivemongo.api.{MongoConnection, MongoDriver}
import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.commands.WriteResult

import scala.collection.mutable.HashMap
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class Main(config: String, evolutions: String) {
  def getConfig(): String =
    this.config

  def getEvolutions(): String =
    this.evolutions
}

object Main extends App {
  if (args.length != 2)

    /**
      * TODO: Add appropriate error checks and handling
      */
    throw new InvalidNumberOfArguments(args)
  val config = args(0)
  val evolutions = args(1)

  val configMap = new HashMap[String, String]()
  for (line <- Source.fromFile(config).getLines()) {
    val pairs: Array[String] = line.split('=')
    if (pairs.length == 2) {
      /**
        * TODO: Add appropriate error checks and handling
        */
      configMap += (pairs(0).trim.replace("\"", "") -> pairs(1).trim.replace("\"", ""))
    }
  }

  lazy val mongoCmd = configMap("mongodb.evolution.mongoCmd")
  lazy val enabled = configMap("mongodb.evolution.enabled").toBoolean
  lazy val mongoURI = configMap("mongodb.uri")
  lazy val dbName = configMap("mongodb.db")
  lazy val applyDownEvolutions = false
  lazy val compareHashes = true
  lazy val applyProdEvolutions = false

  val driver1 = new MongoDriver
  val connection = driver1.connection(List("localhost"))

  val mongoEvolution = new MongevScriptProcessor(mongoCmd, enabled, true, true, true, true)
  mongoEvolution.onStart()
  println("Here we are with the changes.")
}

private[mongodb_migrations] case class Evolution(revision: Int, db_up: String = "", db_down: String = "") {
  val hash = sha1(db_down.trim + db_up.trim)
}

private[mongodb_migrations] object Evolution {
  implicit val evolutionReads = Json.reads[Evolution]
}

private[mongodb_migrations] trait Script {

  val evolution: Evolution

  val script: String
}

private[mongodb_migrations] case class UpScript(evolution: Evolution, script: String) extends Script

private[mongodb_migrations] case class DownScript(evolution: Evolution, script: String) extends Script

private[mongodb_migrations] trait MongevLogger {
  val logger = Logger("mongodb_migrations")
}

private[mongodb_migrations] trait EvolutionHelperScripts {

  def evolutionDBName = "play_evolutions"

  def lockDBName = "play_evolutions_lock"

  val allEvolutionsQuery = evolutionsQuery("")

  val unfinishedEvolutionsQuery = evolutionsQuery( """{"state" : {$in : ["applying_up", "applying_down"]}}""")

  def evolutionsQuery(query: String) =
    s"""
       |cursor = db.$evolutionDBName.find($query).sort( { "revision": -1 } );
       |print("[");
       |while ( cursor.hasNext() ) {
       |  printjson( cursor.next() );
       |  if(cursor.hasNext())
       |    print(",")
       |}
       |print("]");
    """.stripMargin

  def setAsApplied(revision: Int, state: String) =
    s"""
       |db.$evolutionDBName.update({"state" : "$state", "revision" : $revision}, {$$set: {"state" : "applied"}});
    """.stripMargin

  def setLastProblem(revision: Int, lastProblem: String) =
    s"""
       |db.$evolutionDBName.update({"revision" : $revision}, {$$set: {"last_problem" : "$lastProblem"}});
    """.stripMargin

  def updateState(revision: Int, updatedState: String) =
    s"""
       |db.$evolutionDBName.update({"revision" : $revision}, {$$set: {"state" : "$updatedState"}});
    """.stripMargin

  def removeAllInState(revision: Int, state: String) =
    s"""
       |db.$evolutionDBName.remove({"state": "$state", "revision" : $revision});
    """.stripMargin

  def remove(revision: Int) =
    s"""
       |db.$evolutionDBName.remove({"revision" : $revision});
    """.stripMargin

  def insert(js: JsObject) =
    s"""
       |db.$evolutionDBName.insert($js);
    """.stripMargin

  val acquireLock =
    s"""
       |result = db.runCommand({
       |  findAndModify: "$lockDBName",
       |  update: { $$inc: { lock: 1 } },
       |  upsert: true,
       |  new: true
       |});
       |printjson(result)
    """.stripMargin

  val releaseLock =
    s"""
       |result = db.runCommand({
       |  findAndModify: "$lockDBName",
       |  update: { $$inc: { lock: -1 } },
       |  new: true
       |});
       |printjson(result)
    """.stripMargin
}

private[mongodb_migrations] trait MongoScriptExecutor extends MongevLogger {

  import scala.sys.process._

  def mongoCmd: String

  class StringListLogger(var messages: List[String] = Nil, var errors: List[String] = Nil) extends ProcessLogger {

    def out(s: => String) {
      messages ::= s
    }

    def err(s: => String) {
      errors ::= s
    }

    def buffer[T](f: => T): T = f
  }

  private def isWindowsSystem =
    System.getProperty("os.name").startsWith("Windows")

  private def startProcess(app: String, param: String) = {
    val cmd = app + " " + param
    if(isWindowsSystem)
      Process("cmd" :: "/c" :: cmd :: Nil)
    else
      Process(cmd)
  }

  def execute(cmd: String): Option[JsValue] = {
    val input = Files.createTempFile("mongo-script", ".js")

    Files.write(input, cmd.getBytes)
    val jsPath = input.toAbsolutePath.toString

    val processLogger = new StringListLogger
    val result = startProcess(mongoCmd, s"--quiet $jsPath") ! (processLogger)

    val output = processLogger.messages.reverse.mkString("\n")

    result match {
      case 0 if output != "" && !output.contains("I CONTROL  Hotfix KB2731284 or later update is installed, no need to zero-out data files") => //fix mongodb 3.3 on windows 7
        val json = flattenObjectIds(output)
        try {
          Some(Json.parse(json))
        } catch {
          case e: com.fasterxml.jackson.core.JsonParseException =>
            logger.error("Failed to parse json: " + json)
            throw InvalidDatabaseEvolutionScript(json, result, "Failed to parse json result.")
        }
      case 0 =>
        None
      case errorCode =>
        throw InvalidDatabaseEvolutionScript(cmd, errorCode, output + "\n" + processLogger.errors.reverse.mkString("\n"))
    }
  }

  def flattenObjectIds(js: String) = {
    val boidRx = "ObjectId\\(([\"a-zA-Z0-9]*)\\)" r

    boidRx.replaceAllIn(js, m => m.group(1))
  }
}

trait Evolutions extends MongoScriptExecutor with EvolutionHelperScripts with MongevLogger {
  def compareHashes: Boolean

//  def applyFor(path: java.io.File = new java.io.File(".")) {
//    Play.current.plugin[MongevPlugin] map {
//      plugin =>
//        val script = evolutionScript(path, plugin.getClass.getClassLoader)
//        applyScript(script)
//    }
//  }

  def updateEvolutionScript(revision: Int = 1, comment: String = "Generated", ups: String, downs: String)(implicit application: Application) {

    val evolutions = application.path.toPath.resolve(evolutionsFilename(revision))
    Files.createDirectory(application.path.toPath.resolve(evolutionsDirectoryName))

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

  def resolve(revision: Int) {
    execute(setAsApplied(revision, "applying_up"))
    execute(removeAllInState(revision, "applying_down"))
  }

  def checkEvolutionsState() {
    execute(unfinishedEvolutionsQuery) map {
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

        val humanScript = "// --- Rev:" + revision + ", " + (if (state == "applying_up") "Ups" else "Downs") + " - " + hash + "\n\n" + script;

        throw InconsistentDatabase(humanScript, error, revision)
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
      case application =>
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

  private val evolutionsDirectoryName = "conf/evolutions/"

  private def evolutionsFilename(revision: Int): String = evolutionsDirectoryName + revision + ".js"

  private def evolutionsResourceName(revision: Int): String = s"evolutions/$revision.js"

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
          Option(new FileInputStream(evolutionsResourceName(revision)))
        }.map {
          stream =>
            (revision + 1, (revision, Source.fromInputStream(stream)("UTF-8").mkString))
        }
    }.sortBy(_._1).map {
      case (revision, script) => {

        val parsed = Collections.unfoldLeft(("", script.split('\n').toList.map(_.trim))) {
          case (_, Nil) => None
          case (context, lines) => {
            val (some, next) = lines.span(l => !isMarker(l))
            Some((next.headOption.map(c => (mapUpsAndDowns(c), next.tail)).getOrElse("" -> Nil),
              context -> some.mkString("\n")))
          }
        }.reverse.drop(1).groupBy(i => i._1).mapValues {
          _.map(_._2).mkString("\n").trim
        }

        Evolution(
          revision,
          parsed.get(UPS).getOrElse(""),
          parsed.get(DOWNS).getOrElse(""))
      }
    }.reverse

  }

}

class MongevScriptProcessor(mongoCmd: String,
                            enabled: Boolean,
                            applyDownEvolutions: Boolean,
                            compareHashes: Boolean,
                            applyProdEvolutions: Boolean,
                            useLocks: Boolean) extends Evolutions with HandleWebCommandSupport with MongevLogger {

  override def onStart() {
    withLock {
      val script = evolutionScript(app.path)
      val hasDown = script.exists(_.isInstanceOf[DownScript])

      if (!script.isEmpty) {
        app.mode match {
          case Mode.Test => applyScript(script)
          case Mode.Dev => applyScript(script)
          case Mode.Prod if applyProdEvolutions && (applyDownEvolutions || !hasDown) => applyScript(script)
          case Mode.Prod if applyProdEvolutions && hasDown => {
            logger.warn("Your production database needs evolutions, including downs! \n\n" + toHumanReadableScript(script))
            logger.warn("Run with -Dmongodb.evolution.applyProdEvolutions=true and " +
              "-Dmongodb.evolution.applyDownEvolutions=true if you want to run them automatically, " +
              "including downs (be careful, especially if your down evolutions drop existing data)")

            throw InvalidDatabaseRevision(toHumanReadableScript(script))
          }
          case Mode.Prod => {
            logger.warn("Your production database needs evolutions! \n\n" + toHumanReadableScript(script))
            logger.warn("Run with -Dmongodb.evolution.applyProdEvolutions=true " +
              "if you want to run them automatically (be careful)")

            throw InvalidDatabaseRevision(toHumanReadableScript(script))
          }
          case _ => throw InvalidDatabaseRevision(toHumanReadableScript(script))
        }
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

  def handleWebCommand(request: play.api.mvc.RequestHeader, buildLink: play.core.BuildLink, path: java.io.File): Option[play.api.mvc.Result] = {

    val applyEvolutions = """/@evolutions/apply""".r
    val resolveEvolutions = """/@evolutions/resolve/([0-9]+)""".r

    lazy val redirectUrl = request.queryString.get("redirect").filterNot(_.isEmpty).map(_(0)).getOrElse("/")

    request.path match {

      case applyEvolutions() => {
        Some {
          val script = evolutionScript(app.path)
          applyScript(script)
          buildLink.forceReload()
          play.api.mvc.Results.Redirect(redirectUrl)
        }
      }

      case resolveEvolutions(rev) => {
        Some {
          resolve(rev.toInt)
          buildLink.forceReload()
          play.api.mvc.Results.Redirect(redirectUrl)
        }
      }

      case _ => None

    }

  }

}

object OfflineEvolutions extends MongevLogger {

  def Evolutions(appPath: File) = new Evolutions {
    val compareHashes = false
    def mongoCmd = Configuration.load(appPath).getString("mongodb.evolution.mongoCmd").get
  }

  private def isTest: Boolean = Play.maybeApplication.exists(_.mode == Mode.Test)

  def applyScript(appPath: File, classloader: ClassLoader) {
    val ev = Evolutions(appPath)
    val script = ev.evolutionScript(appPath)
    if (!isTest) {
      logger.warn("Applying evolution script for database:\n\n" + ev.toHumanReadableScript(script))
    }
    ev.applyScript(script)
  }

  def resolve(appPath: File, revision: Int) {
    val ev = Evolutions(appPath)
    if (!isTest) {
      logger.warn("Resolving evolution [" + revision + "] for database")
    }
    ev.resolve(revision)
  }

}

case class InvalidDatabaseRevision(script: String) extends PlayException.RichDescription(
  "Database needs evolution!",
  "A MongoDB script need to be run on your database.") {

  def subTitle = "This MongoDB script must be run:"

  def content = script

  private val javascript = """
        document.location = '/@evolutions/apply?redirect=' + encodeURIComponent(location)
                           """.trim

  def htmlDescription = {

    <span>A MongoDB script will be run on your database -</span>
        <input name="evolution-button" type="button" value="Apply this script now!" onclick={javascript}/>

  }.mkString
}

case class InconsistentDatabase(script: String, error: String, rev: Int) extends PlayException.RichDescription(
  "Database is in an inconsistent state!",
  "An evolution has not been applied properly. Please check the problem and resolve it manually before marking it as resolved.") {

  def subTitle = "We got the following error: " + error + ", while trying to run this MongoDB script:"

  def content = script

  private val javascript = """
        document.location = '/@evolutions/resolve/%s?redirect=' + encodeURIComponent(location)
                           """.format(rev).trim

  def htmlDescription: String = {

    <span>An evolution has not been applied properly. Please check the problem and resolve it manually before marking it as resolved -</span>
        <input name="evolution-button" type="button" value="Mark it resolved" onclick={javascript}/>

  }.mkString

}

case class InvalidDatabaseEvolutionScript(script: String, exitCode: Int, error: String) extends PlayException.RichDescription(
  "Evolution failed!",
  s"Tried to run an evolution, but got the following return value: $exitCode") {

  def subTitle = "This MongoDB script produced an error while running on the db:"

  def content = script

  def htmlDescription = {

    <span>Error: "
      {error}
      ".</span>
      <span>Try to fix the issue!</span>

  }.mkString
}

case class InvalidNumberOfArguments(args: Array[String]) extends Exception {
  val numArgs = args.length
  s"""
     |Incorrect number of arguments.
     |Expected: 2
     |Given: $numArgs
     |args(0): File path for the database configuration file.
     |args(1): Folder path for the evolution JavaScript files.
   """.stripMargin
}