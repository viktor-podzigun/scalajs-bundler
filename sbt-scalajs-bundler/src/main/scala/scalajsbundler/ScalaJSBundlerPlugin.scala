package scalajsbundler

import org.scalajs.core.tools.io.{FileVirtualJSFile, VirtualJSFile}
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt._
import sbt.Keys._

object ScalaJSBundlerPlugin extends AutoPlugin {

  override lazy val requires = ScalaJSPlugin

  override lazy val trigger = allRequirements

  // Exported keys
  object autoImport {

    val npmUpdate = taskKey[Unit]("Fetch NPM dependencies")

    val npmDependencies = settingKey[Seq[(String, String)]]("NPM dependencies (libraries that your program uses)")

    val npmDevDependencies = settingKey[Seq[(String, String)]]("NPM dev dependencies (libraries that the build uses)")

    val webpack = taskKey[Seq[File]]("Bundle the output of a Scala.js stage using webpack")

    val webpackConfigFile = settingKey[Option[File]]("Configuration file to use with webpack")

    val webpackEntries = taskKey[Seq[(String, File)]]("Webpack entry bundles")

    val webpackEmitSourceMaps = settingKey[Boolean]("Whether webpack should emit source maps at all")

  }

  val scalaJSBundlerLauncher = taskKey[Launcher]("Launcher generated by scalajs-bundler")

  val scalaJSBundlerConfigFiles = taskKey[ConfigFiles]("Writes the config files")

  val scalaJSBundlerManifest = taskKey[File]("Writes the NPM_DEPENDENCIES file")

  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(

    version in webpack := "1.13",

    webpackConfigFile := None,

    (products in Compile) := (products in Compile).dependsOn(scalaJSBundlerManifest).value,

    scalaJSBundlerManifest := Def.task {
      NpmDependencies.writeManifest(
        NpmDependencies(
          (npmDependencies in Compile).value.to[List],
          (npmDependencies in Test).value.to[List],
          (npmDevDependencies in Compile).value.to[List],
          (npmDevDependencies in Test).value.to[List]
        ),
        (classDirectory in Compile).value
      )
    }.value

  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings ++ testSettings)

  private lazy val perConfigSettings: Seq[Def.Setting[_]] =
    Seq(
      loadedJSEnv := loadedJSEnv.dependsOn(npmUpdate in fastOptJS).value,
      npmDependencies := Seq.empty,
      npmDevDependencies := Seq.empty
    ) ++
    perScalaJSStageSettings(fastOptJS) ++
    perScalaJSStageSettings(fullOptJS)

  private lazy val testSettings: Seq[Setting[_]] =
    Seq(
      npmDependencies ++= (npmDependencies in Compile).value,
      npmDevDependencies ++= (npmDevDependencies in Compile).value
    )

  private def perScalaJSStageSettings(stage: TaskKey[Attributed[File]]): Seq[Def.Setting[_]] = Seq(

    webpack in stage := Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val configFiles = (scalaJSBundlerConfigFiles in stage).value
      val entries = (webpackEntries in stage).value

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / s"${stage.key.label}-webpack",
          inStyle = FilesInfo.hash
        ) { in =>
          Commands.bundle(targetDir, log)
          configFiles.output.to[Set] // TODO Support custom webpack config file (the output may be overridden by users)
        }
      cachedActionFunction(Set(
        configFiles.webpackConfig,
        configFiles.packageJson
      ) ++ entries.map(_._2).to[Set] + stage.value.data).to[Seq] // Note: the entries should be enough, excepted that they currently are launchers, which do not change even if the scalajs stage output changes
    }.dependsOn(npmUpdate in stage).value,

    npmUpdate in stage := Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / "npm-update",
          inStyle = FilesInfo.hash
        ) { _ =>
          Commands.npmUpdate(targetDir, log)
          Set.empty
        }

      cachedActionFunction(Set(targetDir /  "package.json"))
      ()
    }.dependsOn(scalaJSBundlerConfigFiles in stage).value,

    webpackEntries in stage := Def.task {
      val launcherFile = (scalaJSBundlerLauncher in stage).value.file
      val stageFile = stage.value.data
      val name = stageFile.name.stripSuffix(".js")
      Seq(name -> launcherFile)
    }.value,

    scalaJSLauncher in stage := Def.task {
      val launcher = (scalaJSBundlerLauncher in stage).value
      Attributed[VirtualJSFile](FileVirtualJSFile(launcher.file))(
        AttributeMap.empty.put(name.key, launcher.mainClass)
      )
    }.value,

    scalaJSBundlerLauncher in stage := Def.task {
      Launcher.write(
        (crossTarget in stage).value,
        stage.value.data,
        (mainClass in (scalaJSLauncher in stage)).value.getOrElse("No main class detected")
      )
    }.value,

    scalaJSBundlerConfigFiles in stage := Def.task {
      ConfigFiles.writeConfigFiles(
        streams.value.log,
        (crossTarget in stage).value,
        (version in webpack).value,
        (webpackConfigFile in stage).value,
        (webpackEntries in stage).value,
        (webpackEmitSourceMaps in stage).value,
        fullClasspath.value,
        npmDependencies.value,
        npmDevDependencies.value,
        configuration.value
      )
    }.value,

    webpackEmitSourceMaps in stage := (emitSourceMaps in stage).value,

    relativeSourceMaps in stage := (webpackEmitSourceMaps in stage).value

  )

}
