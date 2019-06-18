lazy val akkaHttpVersion = "10.1.8"
lazy val akkaVersion    = "2.6.0-M2"

lazy val root = (project in file(".") enablePlugins (Cinnamon, JavaAppPackaging)).
  settings(
    inThisBuild(List(
      organization    := "com.example",
      scalaVersion    := "2.12.7"
    )),
    name := "akka-http-quickstart-scala",
    cinnamon in run := true,
    cinnamon in test := true,
    libraryDependencies ++= Seq(
      Cinnamon.library.cinnamonCHMetrics,
      Cinnamon.library.cinnamonAkkaStream,
      Cinnamon.library.cinnamonAkkaHttp,
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream"          % akkaVersion,

      "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,
      "org.scalatest"     %% "scalatest"            % "3.0.5"         % Test
    )
  )
