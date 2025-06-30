lazy val zio2Version = "2.1.1"
lazy val zioLoggingVersion = "2.2.4"
lazy val zioPreludeVersion = "1.0.0-RC41"

lazy val zio1Version = "1.0.18"

lazy val jeromqVersion = "0.5.3"

lazy val scala3Version = "3.7.1"
lazy val scala213Version = "2.13.14"
lazy val mainScalaVersion = scala3Version

lazy val supportedScalaVersions = List(scala3Version, scala213Version)

ThisBuild / organization := "io.github.unit-finance"
// ThisBuild / organization := "co.unit"
ThisBuild / organizationName := "Unit"
ThisBuild / organizationHomepage := Some(url("https://unit.co"))
ThisBuild / version := "0.0.1-SNAPSHOT"
ThisBuild / scalaVersion := mainScalaVersion
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / licenses := List(
  "MPL 2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/")
)

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/unit-finance/zio-raft"),
    "scm:git@github.com:unit-finance/zio-raft.git"
  )
)

ThisBuild / homepage := Some(url("https://github.com/unit-finance/zio-raft"))

ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "s01.oss.sonatype.org",
  sys.env.getOrElse("SONATYPE_USERNAME", ""),
  sys.env.getOrElse("SONATYPE_PASSWORD", "")
)

ThisBuild / developers := List(
  Developer(
    id = "somdoron",
    name = "Doron Somech",
    email = "doron@unit.co",
    url = url("https://github.com/somdoron")
  )
)

scalaVersion := mainScalaVersion

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

ThisBuild / scalacOptions ++= Seq(
  "-Wunused:imports",
  "-preview" // enabling for-comprehension improvements for scala 3.7.1 (in >3.8 no need for this flag anymore)
  )

lazy val root = project
  .in(file("."))
  .aggregate(raft, kvstore, zio1zmq, zio2zmq, raftZmq, stores, ziolmdb)
  .settings(
    publish / skip := true,
    crossScalaVersions := Nil
  )

lazy val raft = project
  .in(file("raft"))
  .settings(
    name := "zio-raft",
    scalaVersion := mainScalaVersion,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "dev.zio" %% "zio-nio" % "2.0.0",
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,      
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )

lazy val kvstore = project
  .in(file("kvstore"))
  .settings(
    name := "kvstore",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "dev.zio" %% "zio-http" % "3.0.0-RC8",
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, raftZmq, stores)

lazy val raftZmq = project
  .in(file("raft-zmq"))
  .settings(
    name := "zio-raft-zmq",
    scalaVersion := mainScalaVersion,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "org.scodec" %% "scodec-bits" % "1.1.37",
      "org.scodec" %% "scodec-core" % "2.2.1"
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, zio2zmq)

lazy val zio1zmq = project
  .in(file("zio1-zmq"))
  .settings(
    name := "zio1-zmq",
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio1Version,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5", // latest version for zio 1.0.18
      "org.zeromq" % "jeromq" % jeromqVersion
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => Seq("io.estatico" %% "newtype" % "0.4.4")
        case Some((3, n)) => Seq()
        case _            => Seq()
      }
    },
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => List("-Xsource:3", "-Ymacro-annotations", "-Wunused:imports")
        case Some((3, n)) => List("-Wunused:imports")
        case _            => List()
      }
    }
  )

lazy val zio2zmq = project
  .in(file("zio2-zmq"))
  .settings(
    name := "zio2-zmq",
    scalaVersion := mainScalaVersion,
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "org.zeromq" % "jeromq" % jeromqVersion
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => Seq("io.estatico" %% "newtype" % "0.4.4")
        case Some((3, n)) => Seq()
        case _            => Seq()
      }
    },
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => List("-Xsource:3", "-Ymacro-annotations", "-Wunused:imports")
        case Some((3, n)) => List("-Wunused:imports")
        case _            => List()
      }
    }
  )

lazy val ziolmdb = project
  .in(file("zio-lmdb"))
  .settings(
    name := "zio-lmdb",
    scalaVersion := mainScalaVersion,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-nio" % "2.0.0",
      "dev.zio" %% "zio-streams" % zio2Version,
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,    
      "org.lmdbjava" % "lmdbjava" % "0.9.0"
    )
  )

lazy val stores = project
  .in(file("stores"))
  .settings(
    name := "zio-raft-stores",
    scalaVersion := mainScalaVersion,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "org.scodec" %% "scodec-bits" % "1.1.37",
      "org.scodec" %% "scodec-core" % "2.2.1",
      "dev.zio" %% "zio-nio" % "2.0.0"
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, ziolmdb)