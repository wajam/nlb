import sbt._
import Keys._
import com.typesafe.sbt.SbtStartScript

object NlbBuild extends Build {
  val PROJECT_NAME = "nlb"

  var commonResolvers = Seq(
    // local snapshot support
    ScalaToolsSnapshots,

    // common deps
    "Wajam" at "http://ci1.cx.wajam/",
    "Maven.org" at "http://repo1.maven.org/maven2",
    "Sun Maven2 Repo" at "http://download.java.net/maven/2",
    "Scala-Tools" at "http://scala-tools.org/repo-releases/",
    "Sun GF Maven2 Repo" at "http://download.java.net/maven/glassfish",
    "Oracle Maven2 Repo" at "http://download.oracle.com/maven",
    "Sonatype" at "http://oss.sonatype.org/content/repositories/release",
    "Cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos/",
    "Twitter" at "http://maven.twttr.com/",
    "Scallop" at "http://mvnrepository.com/",
    "spy" at "http://files.couchbase.com/maven2/",
    "Twitter" at "http://maven.twttr.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray Repository" at "http://repo.spray.io",
    "Scallop" at "http://mvnrepository.com/"
  )

  var commonDeps = Seq(
    "org.slf4j" % "slf4j-api" % "1.6.4",
    "org.slf4j" % "slf4j-log4j12" % "1.6.4",
    "log4j" % "log4j" % "1.2.15" exclude("javax.jms", "jms") exclude("com.sun.jmx", "jmxri") exclude("com.sun.jdmk", "jmxtools"),
    "nl.grons" %% "metrics-scala" % "2.2.0" exclude("org.slf4j", "slf4j-api"),
    "com.yammer.metrics" % "metrics-graphite" % "2.2.0" exclude("org.slf4j", "slf4j-api"),
    "com.wajam" %% "nrv-core" % "0.1-SNAPSHOT",
    "com.wajam" %% "nrv-scribe" % "0.1-SNAPSHOT" exclude("log4j", "log4j"),
    "com.wajam" %% "nrv-zookeeper" % "0.1-SNAPSHOT"  exclude("log4j", "log4j"),
    "org.scalatest" %% "scalatest" % "1.9.1" % "test,it",
    "junit" % "junit" % "4.11" % "test,it",
    "org.mockito" % "mockito-core" % "1.9.0" % "test,it",
    "com.typesafe" % "config" % "1.0.2",
    "com.typesafe.akka" % "akka-actor_2.10" % "2.2.0-RC1",
    "com.typesafe.akka" % "akka-testkit_2.10" % "2.2.0-RC1",
    "com.typesafe.akka" % "akka-slf4j_2.10" % "2.2.0-RC1" exclude("org.slf4j", "slf4j-api"),
    "io.spray" % "spray-io" % "1.2-M8",
    "io.spray" % "spray-http" % "1.2-M8",
    "io.spray" % "spray-util" % "1.2-M8",
    "io.spray" % "spray-can" % "1.2-M8"
  )

  val defaultSettings = Defaults.defaultSettings ++ Defaults.itSettings ++ Seq(
    libraryDependencies ++= commonDeps,
    resolvers ++= commonResolvers,
    retrieveManaged := true,
    publishMavenStyle := true,
    organization := "com.wajam",
    version := "0.1-SNAPSHOT",
    scalaVersion := "2.10.2"
  )

  lazy val root = Project(PROJECT_NAME, file("."))
    .configs(IntegrationTest)
    .settings(defaultSettings: _*)
    .settings(testOptions in IntegrationTest := Seq(Tests.Filter(s => s.contains("Test"))))
    .settings(parallelExecution in IntegrationTest := false)
    .settings(SbtStartScript.startScriptForClassesSettings: _*)
}
