import LionPlugin._

lionRuns := 0

lionAllocRuns := 1

name := "rx-playground"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.4"

resolvers ++= Seq(
  Resolver.mavenLocal,
//  Resolver.sonatypeRepo("snapshots"),
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  Resolver.typesafeRepo("releases")
)

libraryDependencies ++= List(
  "com.google.guava"  % "guava" % "16.0",
  "com.netflix.rxjava" % "rxjava-scala" % "0.20.5" intransitive(), // 2.10.x only
  "com.netflix.rxjava" % "rxjava-core" % "0.20.5" intransitive(),
  "com.netflix.rxjava" % "rxjava-string" % "0.20.5" intransitive(),
  "com.typesafe.akka" %% "akka-contrib" % "2.3.6" intransitive(),
  "com.typesafe.akka" %% "akka-actor"   % "2.3.6",
  "org.scalaz.stream" %% "scalaz-stream" % "0.5a",
  "junit" % "junit" % "4.11" % "test"
)

net.virtualvoid.sbt.graph.Plugin.graphSettings
