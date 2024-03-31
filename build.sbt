lazy val zioVersion = "1.0.18"
lazy val zhttpVersion = "1.0.0.0-RC29"
lazy val zioLoggingVersion = "0.5.14"
lazy val mainScalaVersion = "3.3.3"

ThisBuild / organization := "io.github.unit-finance"
ThisBuild / organizationName := "Unit"
ThisBuild / organizationHomepage := Some(url("https://unit.co"))
ThisBuild / version := "0.0.1"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/unit-finance/zio-raft"),
    "scm:git@github.com:unit-finance/zio-raft.git"
  )
)

ThisBuild / homepage := Some(url("https://github.com/unit-finance/zio-raft"))

// ThisBuild / publishTo := {
//   val nexus = "https://s01.oss.sonatype.org/"
//   if (isSnapshot.value)
//     Some("snapshots" at nexus + "content/repositories/snapshots")
//   else Some("releases" at nexus + "service/local/staging/deploy/maven2")
// }

ThisBuild / publishTo := {
  val githubOwner = "unit-finance"
  val githubRepository = "zio-raft"
  
  val nexus = "https://maven.pkg.github.com/" + githubOwner
  if (isSnapshot.value)
    Some("snapshots" at nexus + "/"+ githubRepository + "/snapshots")
  else
    Some("releases"  at nexus + "/"+ githubRepository + "/releases")

}

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "s01.oss.sonatype.org",
  sys.env.getOrElse("SONATYPE_USERNAME", ""),
  sys.env.getOrElse("SONATYPE_PASSWORD", "")
)

ThisBuild / credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    sys.env.getOrElse("GITHUB_USERNAME"),
    sys.env.getOrElse("GITHUB_TOKEN")
  )

scalaVersion := mainScalaVersion

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = project
  .in(file("."))
  .aggregate(raft, kvstore, zmq, raftZmq)
  .settings(
    publish / skip := true,
    publishers +=
  )

lazy val raft = project
  .in(file("raft"))
  .settings(
    name := "zio-raft",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite"),

//    scalacOptions ++= Seq(
//      "-source:future"
//    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "dev.zio" %% "zio-logging" % zioLoggingVersion
    )
  )

lazy val kvstore = project
  .in(file("kvstore"))
  .settings(
    name := "kvstore",
    publish / skip := true,
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "io.d11" %% "zhttp" % zhttpVersion
    )
  )
  .dependsOn(raft, raftZmq)

lazy val raftZmq = project
  .in(file("raft-zmq"))
  .settings(
    name := "zio-raft-zmq",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "org.scodec" %% "scodec-bits" % "1.1.37",
      "org.scodec" %% "scodec-core" % "2.2.1"
    )
  )
  .dependsOn(raft, zmq)

lazy val zmq = project
  .in(file("zmq"))
  .settings(
    name := "zio-zmq",
    scalaVersion := mainScalaVersion,
    scalacOptions ++= Seq("-indent", "-rewrite"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5",
      "org.zeromq" % "jeromq" % "0.5.3"
    )
  )
