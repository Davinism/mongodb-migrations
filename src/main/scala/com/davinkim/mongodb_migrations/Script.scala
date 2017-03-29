package com.davinkim.mongodb_migrations

private[mongodb_migrations] trait Script {

  val evolution: Evolution

  val script: String
}