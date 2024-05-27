lazy val zio2Version = "2.1.1"
lazy val zioLoggingVersion = "2.2.4"

lazy val zio1Version = "1.0.18"

lazy val jeromqVersion = "0.5.3"

lazy val scala3Version = "3.4.2"
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

scalaVersion := mainScalaVersion

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = project
  .in(file("."))
  .aggregate(raft, kvstore, zio1zmq, zio2zmq, raftZmq)
  .settings(
    publish / skip := true,
    crossScalaVersions := Nil,
  )

lazy val raft = project
  .in(file("raft"))
  .settings(
    name := "zio-raft",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite", "-Wunused:imports"),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,      
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC26",
      // "dev.zio" %% "zio-logging" % zioLoggingVersion
    )
  )

lazy val kvstore = project
  .in(file("kvstore"))
  .settings(
    name := "kvstore",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite", "-Wunused:imports"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio1Version,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "dev.zio" %% "zio-http" % "3.0.0-RC7"
    )
  )
  .dependsOn(raft, raftZmq)

lazy val raftZmq = project
  .in(file("raft-zmq"))
  .settings(
    name := "zio-raft-zmq",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite", "-Wunused:imports"),
    libraryDependencies ++= Seq(      
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "org.scodec" %% "scodec-bits" % "1.1.37",
      "org.scodec" %% "scodec-core" % "2.2.1"
    )
  )
  .dependsOn(raft, zio2zmq)

lazy val zio1zmq = project
  .in(file("zio1-zmq"))
  .settings(
    name := "zio1-zmq",
    crossScalaVersions := supportedScalaVersions,    
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio1Version,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "org.zeromq" % "jeromq" % jeromqVersion,
    ),

    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => Seq("io.estatico" %% "newtype" % "0.4.4")
        case Some((3, n)) => Seq()
        case _ => Seq()
      }
    },

    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => List("-Xsource:3", "-Ymacro-annotations", "-Wunused:imports")
        case Some((3, n)) => List("-Wunused:imports")
        case _ => List()
      }
    },
  )

lazy val zio2zmq = project
  .in(file("zio2-zmq"))
  .settings(
    name := "zio2-zmq",
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC26",
      "org.zeromq" % "jeromq" % jeromqVersion,
    ),

    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => Seq("io.estatico" %% "newtype" % "0.4.4")
        case Some((3, n)) => Seq()
        case _ => Seq()
      }
    },

    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) => List("-Xsource:3", "-Ymacro-annotations", "-Wunused:imports")
        case Some((3, n)) => List("-Wunused:imports")
        case _ => List()
      }
    },
  )

