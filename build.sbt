import com.github.oforero.sbtformatter.SbtFormatter._
import com.github.oforero.sbtformatter.SbtFormatterSettings._

name := "rl"

version := "0.0.1-SNAPSHOT"

organization := "com.mojolly.url"

scalaVersion := "2.9.0-1"

scalacOptions ++= Seq("-optimize", "-unchecked", "-deprecation", "-Xcheckinit", "-encoding", "utf8")

libraryDependencies += "org.specs2" %% "specs2" % "1.4" % "test"

testFrameworks += new TestFramework("org.specs2.runner.SpecsFramework")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo <<= (version) { version: String =>
  val nexus = "http://maven.mojolly.com/content/repositories/"
  if (version.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus+"snapshots/")
  else                                   Some("releases" at nexus+"releases/")
}

seq( formatterPreferences : _*) 

seq( 
	indentLocalDefs := false,
	spaceBeforeColon := false,
    spaceInsideBrackets := false,
    spaceInsideParentheses := false,
    preserveDanglingCloseParenthesis := false,
    compactStringConcatenation := false
) 

seq( formatterTasks : _* )
