lazy val zio2Version = "2.1.1"
lazy val zioLoggingVersion = "2.2.4"
lazy val zioPreludeVersion = "1.0.0-RC41"

lazy val zio1Version = "1.0.18"

lazy val jeromqVersion = "0.5.3"

lazy val scala3Version = "3.3.6"
lazy val scala213Version = "2.13.14"
lazy val mainScalaVersion = scala3Version

lazy val supportedScalaVersions = List(scala3Version, scala213Version)

// If you change the organization, you need to update publish.yml github workflow
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
  if (isSnapshot.value)
    Some("snapshots" at "https://central.sonatype.com/repository/maven-snapshots/")
  else Some("releases" at "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2")
}

ThisBuild / credentials += Credentials(
  "OSSRH Staging API Service",
  "ossrh-staging-api.central.sonatype.com",
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
  "Sonatype OSS Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"

lazy val commonScalacOptions = Def.setting {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) => List(
        "-Xsource:3.7-migration",
        "-Ymacro-annotations",
        "-Wunused:imports"
      )
    case Some((3, n)) => List(
        "-Wunused:imports",
        "-source:future",
        "-deprecation"
      )
    case _ => List()
  }
}

lazy val root = project
  .in(file("."))
  .aggregate(
    raft,
    kvstore,
    kvstoreCli,
    kvstoreProtocol,
    zio1zmq,
    zio2zmq,
    raftZmq,
    stores,
    ziolmdb,
    clientServerProtocol,
    clientServerServer,
    clientServerClient,
    sessionStateMachine
  )
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
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-logging" % zioLoggingVersion,
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test,
      "dev.zio" %% "zio-nio" % "2.0.0",
      "dev.zio" %% "zio-prelude" % zioPreludeVersion
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )

lazy val kvstore = project
  .in(file("kvstore"))
  .settings(
    name := "kvstore",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "dev.zio" %% "zio-config" % "4.0.4",
      "dev.zio" %% "zio-config-magnolia" % "4.0.4",
      "dev.zio" %% "zio-logging" % "2.5.1",
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, raftZmq, stores, sessionStateMachine, clientServerProtocol, clientServerServer, kvstoreProtocol)

lazy val raftZmq = project
  .in(file("raft-zmq"))
  .settings(
    name := "zio-raft-zmq",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= commonScalacOptions.value,
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
    scalacOptions ++= commonScalacOptions.value,
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
    }
  )

lazy val zio2zmq = project
  .in(file("zio2-zmq"))
  .settings(
    name := "zio2-zmq",
    scalaVersion := mainScalaVersion,
    crossScalaVersions := supportedScalaVersions,
    scalacOptions ++= commonScalacOptions.value,
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
    }
  )

lazy val ziolmdb = project
  .in(file("zio-lmdb"))
  .settings(
    name := "zio-lmdb",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= commonScalacOptions.value,
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
    scalacOptions ++= commonScalacOptions.value,
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

lazy val clientServerProtocol = project
  .in(file("client-server-protocol"))
  .settings(
    name := "zio-raft-client-server-protocol",
    crossScalaVersions := supportedScalaVersions,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "org.scodec" %% "scodec-bits" % "1.1.37",
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(
          "org.scodec" %% "scodec-core" % "1.11.10"
        )
      case Some((3, _)) => Seq(
          "org.scodec" %% "scodec-core" % "2.3.2"
        )
      case _ => Seq.empty
    }),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )

lazy val clientServerServer = project
  .in(file("client-server-server"))
  .settings(
    name := "zio-raft-client-server-server",
    scalaVersion := mainScalaVersion,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-streams" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, clientServerProtocol, zio2zmq)

lazy val clientServerClient = project
  .in(file("client-server-client"))
  .settings(
    name := "zio-raft-client-server-client",
    crossScalaVersions := supportedScalaVersions,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-streams" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13",
    // Add better-monadic-for compiler plugin for Scala 2
    libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
      case _            => Seq.empty
    })
  )
  .dependsOn(clientServerProtocol, zio2zmq)

lazy val sessionStateMachine = project
  .in(file("session-state-machine"))
  .settings(
    name := "zio-raft-session-state-machine",
    scalaVersion := mainScalaVersion,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= commonScalacOptions.value ++ Seq(
      "-Wvalue-discard",
      "-Xfatal-warnings"
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-prelude" % zioPreludeVersion,
      "org.scodec" %% "scodec-core" % "2.2.2",
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(raft, clientServerProtocol)

lazy val kvstoreProtocol = project
  .in(file("kvstore-protocol"))
  .settings(
    name := "kvstore-protocol",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "org.scodec" %% "scodec-core" % "2.3.2",
      "org.scodec" %% "scodec-bits" % "1.1.37"
    )
  )

lazy val kvstoreCli = project
  .in(file("kvstore-cli"))
  .settings(
    name := "kvstore-cli",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    scalacOptions ++= commonScalacOptions.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-streams" % zio2Version,
      "dev.zio" %% "zio-cli" % "0.7.3",
      "dev.zio" %% "zio-test" % zio2Version % Test,
      "dev.zio" %% "zio-test-sbt" % zio2Version % Test
    ),
    excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13"
  )
  .dependsOn(kvstore, clientServerClient, kvstoreProtocol)
