version := "0.1.0-SNAPSHOT"

scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  "dev.zio"      %% "zio"          % "2.1.26",
  "dev.zio"      %% "zio-prelude"  % "1.0.0-RC47",
  "com.beachape" %% "enumeratum"   % "1.9.8",
  "dev.zio"      %% "zio-test-sbt" % "2.1.26" % Test
)
