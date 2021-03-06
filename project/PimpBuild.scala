import java.nio.file.Paths

import com.mle.appbundler.FileMapping
import com.mle.file.StorageFile
import com.mle.sbt.GenericKeys._
import com.mle.sbt.GenericPlugin
import com.mle.sbt.azure.{AzureKeys, AzurePlugin}
import com.mle.sbt.mac.MacKeys._
import com.mle.sbt.mac.MacPlugin._
import com.mle.sbt.unix.LinuxPlugin
import com.mle.sbt.win.{WinKeys, WinPlugin}
import com.mle.sbtplay.PlayProjects
import com.typesafe.sbt.SbtNativePackager
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.packager.{linux, rpm}
import play.PlayImport.PlayKeys
import sbt.Keys._
import sbt._
import sbtassembly.Plugin.AssemblyKeys._
import sbtassembly.Plugin._
import sbtbuildinfo.Plugin._

object PimpBuild extends Build {

  lazy val pimpProject = PlayProjects.plainPlayProject("musicpimp").settings(playSettings: _*)

  lazy val commonSettings = Seq(
    version := "2.7.3",
    organization := "org.musicpimp",
    scalaVersion := "2.11.4",
    retrieveManaged := false,
    sbt.Keys.fork in Test := true,
    resolvers ++= Seq(
      "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
      "Sonatype releases" at "https://oss.sonatype.org/content/repositories/releases/"),
    javacOptions ++= Seq("-source", "1.7", "-target", "1.7"),
    // for background, see: http://tpolecat.github.io/2014/04/11/scalac-flags.html
    scalacOptions ++= Seq(
      "-target:jvm-1.7",
      "-deprecation",
      "-encoding", "UTF-8",
      "-unchecked",
      "-feature",
      "-language:existentials",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-Xfatal-warnings",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen")
  )

  lazy val nativePackagingSettings = SbtNativePackager.packagerSettings ++
    WinPlugin.windowsSettings ++
    LinuxPlugin.rpmSettings ++
    LinuxPlugin.debianSettings ++
    GenericPlugin.confSettings ++
    AzurePlugin.azureSettings ++
    pimpMacSettings

  def pimpMacSettings = macSettings ++ Seq(
    jvmOptions ++= Seq("-Dhttp.port=8456"),
    launchdConf := Some(defaultLaunchd.value.copy(plistDir = Paths get "/Library/LaunchDaemons")),
    appIcon in Mac := Some((pkgHome in Mac).value / "guitar.icns"),
    pkgIcon := Some((pkgHome in Mac).value / "guitar.png"),
    hideDock := true,
    extraDmgFiles := Seq(
      FileMapping((pkgHome in Mac).value / "guitar.png", Paths get ".background/.bg.png"),
      FileMapping((pkgHome in Mac).value / "DS_Store", Paths get ".DS_Store")
    )
  )

  val mleGroup = "com.github.malliina"
  val httpGroup = "org.apache.httpcomponents"
  val httpVersion = "4.3.5"

  lazy val playSettings = assemblyConf ++
    buildMetaSettings ++
    commonSettings ++
    nativePackagingSettings ++
    net.virtualvoid.sbt.graph.Plugin.graphSettings ++
    Seq(
      libraryDependencies ++= Seq(
        mleGroup %% "util-base" % "0.3.0",
        mleGroup %% "util-play" % "1.7.1",
        mleGroup %% "play-base" % "0.2.2",
        mleGroup %% "util-actor" % "1.5.0",
        mleGroup %% "util-rmi" % "1.5.0",
        mleGroup %% "util-audio" % "1.4.4",
        mleGroup %% "logback-rx" % "0.1.2",
        mleGroup %% "mobile-push" % "0.1.1",
        httpGroup % "httpclient" % httpVersion,
        httpGroup % "httpmime" % httpVersion,
        play.PlayImport.filters,
        "net.glxn" % "qrgen" % "1.4",
        "it.sauronsoftware.cron4j" % "cron4j" % "2.2.5",
        "com.h2database" % "h2" % "1.3.176",
        "com.typesafe.slick" %% "slick" % "2.1.0",
        "org.java-websocket" % "Java-WebSocket" % "1.3.0").map(dep => dep withSources()),
      mainClass := Some("com.mle.musicpimp.Starter"),
      linux.Keys.maintainer := "Michael Skogberg <malliina123@gmail.com>",
      // why conf?
      linux.Keys.packageSummary in Linux := "MusicPimp summary here.",
      rpm.Keys.rpmVendor := "Skogberg Labs",
      manufacturer := "Skogberg Labs",
      displayName := "MusicPimp",
      // never change
      WinKeys.upgradeGuid := "5EC7F255-24F9-4E1C-B19D-581626C50F02",
      AzureKeys.azureContainerName := "files",
      WinKeys.minJavaVersion := Some(7),
      WinKeys.postInstallUrl := Some("http://localhost:8456"),
      appIcon in Windows := Some((pkgHome in Windows).value / "guitar-128x128-np.ico"),
      resolvers ++= Seq(
        "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
        "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/")
    )

  def buildMetaSettings = buildInfoSettings ++ Seq(
    sourceGenerators in Compile <+= buildInfo,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion),
    buildInfoPackage := "com.mle.musicpimp"
  )

  def assemblyConf = assemblySettings ++ Seq(
    jarName in assembly := s"app-${version.value}.jar",
    test in assembly :=(),
    fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value),
    mergeStrategy in assembly <<= (mergeStrategy in assembly)((old: (String => MergeStrategy)) => {
      case "application.conf" =>
        MergeStrategy.concat
      case x if (x startsWith """org\apache\commons\logging""") || (x startsWith """play\core\server""") =>
        MergeStrategy.last
      case x if x startsWith """rx\""" =>
        MergeStrategy.first
      case "logger.xml" =>
        MergeStrategy.first
      case x =>
        old(x)
    })
  )
}