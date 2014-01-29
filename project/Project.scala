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
    "Scallop" at "http://mvnrepository.com/",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray Repository" at "http://repo.spray.io",
    "Spray Nightly" at "http://nightlies.spray.io"
  )

  var commonDeps = Seq(
    "nl.grons" %% "metrics-scala" % "2.2.0",
    "com.yammer.metrics" % "metrics-graphite" % "2.2.0",
    "com.wajam" %% "commons-core" % "0.1-SNAPSHOT",
    "com.wajam" %% "commons-tracing" % "0.1-SNAPSHOT",
    "com.wajam" %% "nrv-core" % "0.1-SNAPSHOT",
    "com.wajam" %% "nrv-scribe" % "0.1-SNAPSHOT",
    "com.wajam" %% "nrv-zookeeper" % "0.1-SNAPSHOT",
    "org.scalatest" %% "scalatest" % "2.0" % "test,it",
    "junit" % "junit" % "4.11" % "test,it",
    "org.mockito" % "mockito-core" % "1.9.0" % "test,it",
    "com.typesafe" % "config" % "1.0.2",
    "com.typesafe.akka" %% "akka-actor" % "2.2.3",
    "com.typesafe.akka" %% "akka-testkit" % "2.2.3",
    "com.typesafe.akka" %% "akka-slf4j" % "2.2.3",
    "io.spray" % "spray-io" % "1.2.0",
    "io.spray" % "spray-http" % "1.2.0",
    "io.spray" % "spray-util" % "1.2.0",
    "io.spray" % "spray-can" % "1.2.0"
  ).map { module =>
    module.excludeAll(
      ExclusionRule(organization = "org.slf4j"),
      ExclusionRule(organization = "org.apache.logging.log4j"),
      ExclusionRule(organization = "log4j")
    )
  } ++ Seq(
    "org.slf4j" % "slf4j-api" % "1.6.4",
    "org.slf4j" % "slf4j-log4j12" % "1.6.4",
    "log4j" % "log4j" % "1.2.15" exclude("javax.jms", "jms") exclude("com.sun.jmx", "jmxri") exclude("com.sun.jdmk", "jmxtools")
  )

  val defaultSettings = Defaults.defaultSettings ++ Defaults.itSettings ++ Seq(
    libraryDependencies ++= commonDeps,
    resolvers ++= commonResolvers,
    retrieveManaged := true,
    publishMavenStyle := true,
    organization := "com.wajam",
    version := "0.1-SNAPSHOT",
    scalaVersion := "2.10.2",
    scalacOptions ++= Seq( "-deprecation", "-unchecked", "-feature" )
  )

  lazy val root = Project(PROJECT_NAME, file("."))
    .configs(IntegrationTest)
    .settings(defaultSettings: _*)
    .settings(testOptions in IntegrationTest := Seq(Tests.Filter(s => s.contains("Test"))))
    .settings(parallelExecution in IntegrationTest := false)
    .settings(SbtStartScript.startScriptForClassesSettings: _*)
    .settings(unmanagedClasspath in Runtime <+= (baseDirectory) map { bd => Attributed.blank(bd / "etc") })

}
