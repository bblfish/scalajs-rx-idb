import bintray.Plugin._
import bintray.Keys._
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt.Keys._
import sbt._

object Build extends sbt.Build {

  lazy val `scalajs-rx-idb` =
    project.in(file("."))
      .enablePlugins(ScalaJSPlugin)
      .settings( publicationSettings ++ Seq(
        organization := "com.viagraphs",
        name := "scalajs-rx-idb",
        version := "0.0.8-lean-SNAPSHOT",
        scalaVersion := "2.11.7",
        scalacOptions ++= Seq(
          "-unchecked", "-deprecation", "-feature", "-Xfatal-warnings",
          "-Xlint", "-Xfuture",
          "-Yinline-warnings", "-Ywarn-adapted-args", "-Ywarn-inaccessible",
          "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Yno-adapted-args"
        ),
        libraryDependencies ++= Seq(
          "org.scala-js" %%% "scalajs-dom" % "0.8.1",
          "org.monifu" %%% "monifu" % "1.0-RC3",
          "com.lihaoyi" %%% "utest" % "0.3.1" % "test",
          "com.github.japgolly.fork.scalaz" %%% "scalaz-core" % "7.2.0" % "test"
        ),
        scalaJSUseRhino := false,
        testFrameworks += new TestFramework("utest.runner.Framework"),
        autoAPIMappings := true,
        requiresDOM := true,
        persistLauncher in Test := false,
        publishMavenStyle := true,
        publishArtifact in Test := false,
        pomIncludeRepository := { _ => false },
        pomExtra :=
          <url>https://github.com/viagraphs/scalajs-rx-idb</url>
            <licenses>
              <license>
                <name>The MIT License (MIT)</name>
                <url>http://opensource.org/licenses/MIT</url>
                <distribution>repo</distribution>
              </license>
            </licenses>
            <scm>
              <url>git@github.com:viagraphs/scalajs-rx-idb.git</url>
              <connection>scm:git:git@github.com:viagraphs/scalajs-rx-idb.git</connection>
            </scm>
            <developers>
              <developer>
                <id>l15k4</id>
                <name>Jakub Liska</name>
                <email>liska.jakub@gmail.com</email>
              </developer>
            </developers>
      ))

  //sbt -Dbanana.publish=bblfish.net:/home/hjs/htdocs/work/repo/
  //sbt -Dbanana.publish=bintray
  def publicationSettings =
    (Option(System.getProperty("banana.publish")) match {
      case Some("bintray") => Seq(
        // bintray
        repository in bintray := "banana-rdf",
        bintrayOrganization in bintray := None
      ) ++ bintrayPublishSettings
      case opt: Option[String] => {
        Seq(
          publishTo <<= version { (v: String) =>
            val nexus = "https://oss.sonatype.org/"
            val other = opt.map(_.split(":"))
            if (v.trim.endsWith("SNAPSHOT")) {
              val repo = other.map(p => Resolver.ssh("banana.publish specified server", p(0), p(1) + "snapshots"))
              repo.orElse(Some("snapshots" at nexus + "content/repositories/snapshots"))
            } else {
              val repo = other.map(p => Resolver.ssh("banana.publish specified server", p(0), p(1) + "releases"))
              repo.orElse(Some("releases" at nexus + "service/local/staging/deploy/maven2"))
            }
          }
        )
      }
    }) ++ Seq(publishArtifact in Test := false)

}