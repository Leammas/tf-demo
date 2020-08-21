name := "tf-demo"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies += "ru.tinkoff" %% "tofu" % "0.7.9"
libraryDependencies += "ru.tinkoff" %% "tofu-logging" % "0.7.9"

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
addCompilerPlugin("org.typelevel"  % "kind-projector_2.13.3" % "0.11.0")