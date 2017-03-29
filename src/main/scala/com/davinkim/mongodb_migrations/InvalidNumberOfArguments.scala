package com.davinkim.mongodb_migrations

case class InvalidNumberOfArguments(args: Array[String]) extends Exception {
  val numArgs = args.length
  s"""
     |Incorrect number of arguments.
     |Expected: 2
     |Given: $numArgs
     |args(0): File path for the database configuration file.
     |args(1): Folder path for the evolution JavaScript files.
   """.stripMargin
}