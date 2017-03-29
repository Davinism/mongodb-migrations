package com.davinkim.mongodb_migrations

import com.davinkim.Exceptions.MigrationException

case class InvalidDatabaseRevision(script: String) extends MigrationException.RichDescription(
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