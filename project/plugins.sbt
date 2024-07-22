addSbtPlugin("org.scala-js"  % "sbt-scalajs"         % "1.15.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")

addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta43")

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// for reading npmDependencies from package.json
libraryDependencies ++= Seq("com.lihaoyi" %% "upickle" % "4.0.0")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.12")
