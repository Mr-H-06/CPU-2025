name := "cpu"
version := "0.1.0"
scalaVersion := "2.12.13"

libraryDependencies ++= Seq(
  "edu.berkeley.cs" %% "chisel3" % "3.5.0",
  "edu.berkeley.cs" %% "chiseltest" % "0.5.0" % "test",
  "org.scalatest" %% "scalatest" % "3.2.9" % "test",
)

addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.5.0" cross CrossVersion.full)