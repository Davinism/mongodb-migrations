name := "mongodb-migrations"

version := "1.0"

scalaVersion := "2.11.8"

assemblyJarName in assembly := "migration.jar"
mainClass in assembly := Some("com.davinkim.mongodb_migrations.Main")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

libraryDependencies += specs2 % Test
libraryDependencies += "javax.inject" % "javax.inject" % "1"
libraryDependencies += "commons-codec" % "commons-codec" % "1.10"
libraryDependencies += "com.fasterxml.jackson.core" % "jackson-core" % "2.5.4"
libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.11.6"
libraryDependencies += "org.scala-lang" % "scala-library" % "2.11.6"
libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.5.4"
libraryDependencies += "org.scala-stm" % "scala-stm_2.11" % "0.7"
libraryDependencies += "org.scalaz" % "scalaz-concurrent_2.11" % "7.1.1"
libraryDependencies += "org.joda" % "joda-convert" % "1.7"
libraryDependencies += "joda-time" % "joda-time" % "2.8.1"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.12"
libraryDependencies += "org.slf4j" % "jcl-over-slf4j" % "1.7.12"
libraryDependencies += "org.slf4j" % "jul-to-slf4j" % "1.7.12"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.12"
libraryDependencies += "com.typesafe.play" % "play-json_2.11" % "2.4.6"