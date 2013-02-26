libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2-M1.1" % "compile",
  "io.spray" % "spray-io" % "1.1-M7" % "compile",
  "org.specs2" %% "specs2" % "1.13" % "test"
)

resolvers ++= Seq(
  "akka snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
  "spray" at "http://repo.spray.io/"
)

scalaVersion := "2.10.0"

spray.revolver.RevolverPlugin.Revolver.settings
