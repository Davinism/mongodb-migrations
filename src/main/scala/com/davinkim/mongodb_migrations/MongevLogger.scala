package com.davinkim.mongodb_migrations

import com.davinkim.Logger.Logger

private[mongodb_migrations] trait MongevLogger {
  val logger = Logger("mongodb_migrations")
}