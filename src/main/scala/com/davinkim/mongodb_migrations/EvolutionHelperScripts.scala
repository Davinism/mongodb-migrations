package com.davinkim.mongodb_migrations

import play.api.libs.json._

private[mongodb_migrations] trait EvolutionHelperScripts {

  def evolutionDBName = "mongo_evolutions"

  def lockDBName = "mongo_evolutions_lock"

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