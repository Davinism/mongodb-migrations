name := "mongodb-migrations"

version := "1.0"

scalaVersion := "2.11.8"

assemblyJarName in assembly := "migration.jar"
mainClass in assembly := Some("com.davinkim.mongodb_migrations.Main")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

libraryDependencies += specs2 % Test
libraryDependencies += "javax.inject" % "javax.inject" % "1"
libraryDependencies += "com.typesafe.play" % "play_2.11" % "2.4.6"
libraryDependencies += "org.reactivemongo" % "reactivemongo_2.11" % "0.11.14"