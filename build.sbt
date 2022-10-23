name                           := "Odor"
ThisBuild / organization       := "com.github.fdietze"
ThisBuild / crossScalaVersions := Seq("2.13.8")
ThisBuild / scalaVersion       := "2.13.8"

inThisBuild(
  List(
    organization := "com.github.fdietze",
    homepage     := Some(url("https://github.com/fdietze/odor")),
    licenses     := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        "fdietze",
        "Felix Dietze",
        "mail@felx.me",
        url("https://github.com/fdietze"),
      ),
    ),
  ),
)

val versions = new {
  val scalaTest = "3.2.14"
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
  scalacOptions += "-Wconf:src=src_managed/.*:s", // silence warnings for generated sources
  scalacOptions ++= (if (isScala3.value) Nil
                     else
                       Seq(
                         "-Vimplicits",
                         "-Vtype-diffs",
                         "-Xasync",
                         "-Ymacro-annotations",
                         "-Xcheckinit",
                       )), // better error messages for implicit resolution
  scalacOptions ++= (if (isScala3.value) Seq("-Yretain-trees") else Nil), // recursive data structures with Scala 3
  scalacOptions ++= (if (isScala3.value) Seq("-scalajs") else Nil),       // needed for Scala3 + ScalaJS

  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %%% "scala-async" % "1.0.1",
    "org.scalatest"          %%% "scalatest"   % versions.scalaTest % Test,
  ),
)

lazy val odor = project
  .enablePlugins(
    ScalaJSPlugin,
    ScalaJSBundlerPlugin,
    ScalablyTypedConverterGenSourcePlugin, // because it's a library: https://scalablytyped.org/docs/library-developer#add-to-your-projectpluginssbt
  )
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fdietze.skunk" %%% "skunk-core" % "3826f7f",
    ),
    Compile / npmDependencies    ++= readJsDependencies(baseDirectory.value, "dependencies"),
    Compile / npmDevDependencies ++= readJsDependencies(baseDirectory.value, "devDependencies"),
    stIgnore ++= List(
      "pg-connection",
    ),
    stOutputPackage := "odor.facades",
    stMinimize      := Selection.All,
    /* but keep these very specific things*/
    stMinimizeKeep ++= List(
      "pg.mod.ClientConfig",
      "pg.mod.QueryArrayConfig",
      "pg.mod.Client",
    ),
    useYarn := true, // Makes scalajs-bundler use yarn instead of npm
  )
