name                           := "Odor"
ThisBuild / organization       := "com.github.fdietze"
ThisBuild / crossScalaVersions := Seq("2.13.8", "3.1.3")
ThisBuild / scalaVersion       := "2.13.8"

val versions = new {
  val scalaTest = "3.2.12"
}

ThisBuild / resolvers ++= Seq(
  "jitpack" at "https://jitpack.io",
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype OSS Snapshots S01" at "https://s01.oss.sonatype.org/content/repositories/snapshots", // https://central.sonatype.org/news/20210223_new-users-on-s01/
)

lazy val scalaJsMacrotaskExecutor = Seq(
  // https://github.com/scala-js/scala-js-macrotask-executor
  libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.0",
)

def readJsDependencies(baseDirectory: File, field: String): Seq[(String, String)] = {
  val packageJson = ujson.read(IO.read(new File(s"$baseDirectory/package.json")))
  packageJson(field).obj.mapValues(_.str).toSeq
}

val enableFatalWarnings =
  sys.env.get("ENABLE_FATAL_WARNINGS").flatMap(value => scala.util.Try(value.toBoolean).toOption).getOrElse(false)

val isScala3 = Def.setting(CrossVersion.partialVersion(scalaVersion.value).exists(_._1 == 3))

lazy val commonSettings = Seq(
  // overwrite scalacOptions "-Xfatal-warnings" from https://github.com/DavidGregory084/sbt-tpolecat
  scalacOptions --= (if (enableFatalWarnings) Nil else Seq("-Xfatal-warnings")),
  scalacOptions ++= (if (isScala3.value) Nil
                     else Seq("-Vimplicits", "-Vtype-diffs")), // better error messages for implicit resolution
  scalacOptions ++= (if (isScala3.value) Seq("-Yretain-trees") else Nil), // recursive data structures with Scala 3
  scalacOptions ++= (if (isScala3.value) Seq("-scalajs") else Nil),       // needed for Scala3 + ScalaJS
)

lazy val odor = project
  .enablePlugins(
    ScalaJSPlugin,
    ScalaJSBundlerPlugin,
  )
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fdietze.skunk" %%% "skunk-core"  % "3826f7f",
    ),
    Compile / npmDependencies    ++= readJsDependencies(baseDirectory.value, "dependencies"),
    Compile / npmDevDependencies ++= readJsDependencies(baseDirectory.value, "devDependencies"),
    useYarn                       := true,// Makes scalajs-bundler use yarn instead of npm
  )
