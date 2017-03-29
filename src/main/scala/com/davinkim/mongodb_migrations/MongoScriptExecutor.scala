package com.davinkim.mongodb_migrations

import java.nio.file.Files
import play.api.libs.json._

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
    val result = startProcess(mongoCmd, s"--quiet $jsPath") ! processLogger

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