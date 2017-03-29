package com.davinkim.mongodb_migrations

import com.davinkim.Exceptions.MigrationException

case class InconsistentDatabase(script: String, error: String, rev: Int) extends MigrationException.RichDescription(
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