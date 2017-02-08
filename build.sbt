//import play.PlayImport.PlayKeys._

name := "mongodb-migrations"

version := "1.0"

scalaVersion := "2.11.8"

assemblyJarName in assembly := "migration.jar"
mainClass in assembly := Some("Main")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

libraryDependencies += specs2 % Test
libraryDependencies += "javax.inject" % "javax.inject" % "1"
libraryDependencies += "com.typesafe.play" % "play_2.11" % "2.4.6"

//lazy val root = (project in file(".")).enablePlugins(PlayScala)