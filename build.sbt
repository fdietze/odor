name                           := "Odor"
ThisBuild / organization       := "com.github.fdietze"
ThisBuild / crossScalaVersions := Seq("2.13.15")
ThisBuild / scalaVersion       := "2.13.15"

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
  val scalaTest = "3.2.18"
}

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
//    if (isScala3.value) "com.github.rssh" %% "shim-scala-async-dotty-cps-async" % "0.9.11"
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
      "org.tpolecat" %%% "skunk-core" % "0.6.1",
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
      "pg.mod.Client",
      "pg.mod.PoolClient",
      "pg.mod.QueryArrayConfig",
      "pgPool.mod.^",
      "pgPool.mod.Config",
    ),
    useYarn        := true, // Makes scalajs-bundler use yarn instead of npm
    yarnExtraArgs ++= Seq("--prefer-offline", "--pure-lockfile"),
    Test / testOptions += Tests.Argument(
      TestFrameworks.ScalaTest,
      // show a few lines of stack traces, see https://www.scalatest.org/user_guide/using_scalatest_with_sbt
      "-oS",
    ),
  )
