name := "rx-playground"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.3"

resolvers ++= Seq(
  Resolver.mavenLocal,
//  Resolver.sonatypeRepo("snapshots"),
  Resolver.typesafeRepo("releases")
)

libraryDependencies ++= List(
  "com.google.guava" % "guava" % "16.0",
  "com.netflix.rxjava" % "rxjava-scala" % "0.17.0-RC1" intransitive(),
  "com.netflix.rxjava" % "rxjava-core" % "0.17.0-RC1" intransitive(),
  "com.netflix.rxjava" % "rxjava-string" % "0.17.0-RC1" intransitive(),
  "com.typesafe.akka"      %% "akka-contrib" % "2.2.1" intransitive(),
  "com.typesafe.akka"      %% "akka-actor"   % "2.2.1"
)

ideaExcludeFolders ++= Seq(".idea", ".idea_modules")

net.virtualvoid.sbt.graph.Plugin.graphSettings
