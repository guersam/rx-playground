// import LionPlugin._

// lionRuns := 0

// lionAllocRuns := 1

// lionAllocTrace := Map (
//   "java/util/regex/Pattern$GroupHead" -> 1048576,
//   "java/util/regex/Pattern" -> 1048576,
//   "scala/concurrent/impl/ExecutionContextImpl$DefaultThreadFactory$$anon$2$$anon$5" -> 1048576,
//   "char" ->  1048576,
//   "scala/concurrent/forkjoin/ForkJoinTask" -> 1048576,
//   "java/util/regex/Matcher" -> 1048576,
//   "int" -> 1048576,
//   "java/lang/String" -> 1048576,
//   "byte" -> 1048576,
//   "java/lang/Object" -> 1048576,
//   "scala/runtime/ObjectRef" -> 1048576,
//   "scalaz/std/FunctionInstances$$anon$1$$anonfun$map$1" -> 1048576,
//   "scalaz/$minus$bslash$div" -> 1048576,
//   "scalaz/Free$$anonfun$flatMap$2" -> 1048576,
//   "scalaz/Free$$anon$3" -> 1048576,
//   "scalaz/stream/Process$Append" -> 1048576,
//   "scala/collection/immutable/VectorBuilder" -> 1048576,
//   "scala/collection/immutable/Vector" -> 1048576,
//   "scala/collection/immutable/VectorIterator" -> 1048576,
//   "scalaz/Free$Return" -> 1048576
// )

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

fork := true
