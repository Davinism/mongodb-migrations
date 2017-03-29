package com.davinkim.mongodb_migrations

import scala.io.Source

class Main(config: String, evolutions: String) {
  def getConfig: String =
    this.config

  def getEvolutions: String =
    this.evolutions
}

object Main extends App {
  lazy val configMongoCmd = "mongodb.evolution.mongoCmd"
  lazy val configEnabled = "mongodb.evolution.enabled"
  lazy val configApplyDownEvolutions = "mongodb.evolution.applyDownEvolutions"
  lazy val configCompareHashesBool = "mongodb.evolution.compareHashes"
  lazy val configApplyProdEvolutions = "mongodb.evolution.applyProdEvolutions"
  lazy val configUseLocks = "mongodb.evolution.useLocks"

  if (args.length != 2) throw new InvalidNumberOfArguments(args)

  val config = args(0)
  val evolutions = args(1)

  val configPairs = for {
    line <- Source.fromFile(config).getLines().toIndexedSeq
    configPair = line.split("=")
    if configPair.length == 2
  } yield (configPair(0).trim.replace("\"", ""), configPair(1).trim.replace("\"", ""))

  val configMap = configPairs.toMap

  val mongoCmdString = configMap.getOrElse(configMongoCmd, throw new MissingConfigException(configMongoCmd))
  val enabled = configMap.getOrElse(configEnabled, throw new MissingConfigException(configEnabled)).toBoolean
  val applyDownEvolutions = configMap.getOrElse(configApplyDownEvolutions, "false").toBoolean
  val compareHashesBool = configMap.getOrElse(configCompareHashesBool, "true").toBoolean
  val applyProdEvolutions = configMap.getOrElse(configApplyProdEvolutions, "false").toBoolean
  val useLocks = configMap.getOrElse(configUseLocks, "true").toBoolean

  val mongoEvolution = new MongevScriptProcessor(
    mongoCmdString,
    enabled,
    applyDownEvolutions,
    compareHashesBool,
    applyProdEvolutions,
    useLocks,
    evolutions
  )
  mongoEvolution.onStart()
  println("Migration completed!")
}