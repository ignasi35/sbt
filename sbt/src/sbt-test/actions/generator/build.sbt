val buildInfo = taskKey[Seq[File]]("generates the build info")

ThisBuild / scalaVersion := "2.12.10"

lazy val root = (project in file("."))
  .settings(
    buildInfo := {
      val file = sourceManaged.value / "BuildInfo.scala"
      IO.write(file, "object BuildInfo")
      file :: Nil
    },
    sourceGenerators in Compile += buildInfo,
    sourceGenerators in Compile += Def.task { Nil }
  )
