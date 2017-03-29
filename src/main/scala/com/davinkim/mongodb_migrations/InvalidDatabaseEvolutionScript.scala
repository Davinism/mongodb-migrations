package com.davinkim.mongodb_migrations

import com.davinkim.Exceptions.MigrationException

case class InvalidDatabaseEvolutionScript(script: String, exitCode: Int, error: String) extends MigrationException.RichDescription(
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