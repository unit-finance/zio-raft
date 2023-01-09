lazy val zioVersion = "1.0.8"


scalaVersion := "3.1.0"

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"



lazy val root = project
  .in(file("."))
  .aggregate(raft)

lazy val raft = project
  .in(file("raft"))
  .settings(
    name := "zio-raft",
    scalaVersion := "3.1.0",
//    scalacOptions ++= Seq(
//      "-source:future"
//    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC5"

    )

  )
