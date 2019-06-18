Trying to imitate an Akka Stream that has 7 stages, each of which hit an HTTP endpoint and then move on to the next stage.

Build with 

```bash
sbt universal:packageBin
```

then scp onto the servers

Run the Akka HTTP server

```bash
./akka-http-quickstart-scala-0.1.0-SNAPSHOT/bin/quickstart-server -Destimator.server.delay="1 send"
```

Providing whatever delay value you want to set.

Then start the Estimator which will run the stream and hit the server 7 times for each message moving through the stream

```
./akka-http-quickstart-scala-0.1.0-SNAPSHOT/bin/estimator -Destimator.parallelism=4 -Destimator.url="http://[server]:8080/users"
```

Set the parallelism value to whatever you want each stage's `mapAsync` parallelism value to be - and provide the endpoint url