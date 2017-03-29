package com.davinkim.mongodb_migrations

class MissingConfigException(configKey: String)
  extends RuntimeException(configKey + " is not set in the .conf file.")