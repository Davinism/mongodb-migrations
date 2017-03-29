package com.davinkim.mongodb_migrations

private[mongodb_migrations] case class DownScript(evolution: Evolution, script: String) extends Script