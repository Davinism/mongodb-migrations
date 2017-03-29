package com.davinkim.mongodb_migrations

private[mongodb_migrations] case class UpScript(evolution: Evolution, script: String) extends Script