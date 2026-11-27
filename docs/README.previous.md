# Sales Batch (dummy app)

A Spring 4.3 + Activiti 5.22 workflow (Java 1.8) that:

1. loads a sales CSV into **HBase 1.4**,
2. runs a **Spark 2.4 (Scala 2.11)** aggregation (target: EMR 5.36),
3. waits for a **manager approval**,
4. publishes `report-<businessDate>.csv`.

## Quick start for teammates (Windows, same setup as the developer machine)

HBase and the Spark cluster (1 master, 2 workers) run in Docker. The Java API, the Scala Spark job and the Angular UI run directly on your Windows machine, so you can debug them in VS Code.

**Install once:** JDK 8 (Temurin 8) with `JAVA_HOME` set to it, Maven 3.6+, Node.js 20.19+, Docker Desktop or Rancher Desktop (running), VS Code with the extensions *Extension Pack for Java* and *Scala (Metals)*. About 6 GB of free RAM and internet access for the first build.

1. **Unzip** `sales-batch-poc.zip` into a folder (a path without spaces is best).
2. **Run `setup-local.cmd`** (double-click, or `.\setup-local.cmd` in Command Prompt). It checks the tools, builds the Spark image and the Java and Scala code, installs the UI packages, writes `.vscode\settings.json` for your machine and starts HBase and the Spark cluster in Docker. The first run takes several minutes.
3. **Open the folder in VS Code** (start VS Code from a new terminal so it sees `JAVA_HOME`). Accept the Metals import, then run `node scripts\fix-bloop.js` and **Metals: Restart build server**.
4. **Start the UI:** `cd ui`, then `npm start`, open http://localhost:4300.
5. **Debug the API:** Run and Debug, pick **Debug app (real HBase + Scala Spark job)**, F5. Upload `sample-data\sales-20261115.csv` from the UI, wait for `WAITING_APPROVAL`, approve. The report appears in `reports`. Details, breakpoints in Java and Scala, and the Docker Spark cluster mode: see "Debug in the IDE (VS Code)" below.

Next time only start the containers (`docker compose -f docker/local/docker-compose.yml up -d`) and press F5. Stop them with `docker compose -f docker/local/docker-compose.yml down`.

## Modules

| Module | What it is |
|---|---|
| `common` | HBase access layer (`SalesRepository`, row keys, schema) |
| `spark-job` | Scala Spark job, built as a fat jar (`spark-job-1.0.0-SNAPSHOT.jar`) |
| `workflow-app` | Spring MVC + Activiti on embedded Jetty. REST API plus Swagger UI. Built as `workflow-app.jar` |
| `docker/` | Dockerfile (Java 8 + Spark 2.4.8) and `hbase-site.xml` for the local setup |
| `sample-data/` | Example CSV |

## Flow

```
POST /batches  ->  202 QUEUED at once; then in the background (Activiti job executor):
                   RUNNING: load CSV into HBase (sales_raw) -> spark-submit aggregates into sales_agg
                   -> WAITING_APPROVAL   (or FAILED, with an error message)
GET /batches/{id}  ->  poll the status until it is WAITING_APPROVAL or FAILED
POST /batches/{id}/approve?approved=true   ->  report-<date>.csv written  ->  COMPLETED
POST /batches/{id}/approve?approved=false  ->  nothing published          ->  REJECTED
```

The process definition is `workflow-app/src/main/resources/processes/sales-batch.bpmn20.xml`.

## Prerequisites

To build and run the unit tests:

- **JDK 8** (Temurin 8 tested). Newer JDKs are not supported by this dependency set.
- **Maven 3.6+** (3.9.9 tested).
- Internet access to Maven Central.

To run the app locally you also need **Docker** (Rancher Desktop or Docker Desktop, daemon running). Docker provides HBase and Spark, which do not run well natively on Windows.

To run on EMR instead, you need an EMR 5.36 master node with Spark and HBase.

VS Code extensions: *Extension Pack for Java* (includes Maven for Java and Test Runner for Java). To debug the Scala Spark job you also need *Scala (Metals)*, see "Debug in the IDE" below.

## Build

    mvn clean package

This compiles all modules, runs the tests (67: common 30, spark-job 7, workflow-app 30) and produces:

- `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` (about 29 MB)
- `workflow-app/target/workflow-app.jar` (about 55 MB)

Most tests use mocks or in-memory fakes for HBase and spark-submit, so they need no cluster or Docker. Two tests in `spark-job` (`SalesAggregatorHBaseTest`) run the real Scala job against a real HBase on `localhost` (ZooKeeper 2181, master 16000, region server 16020) and are **skipped automatically when that HBase is not running**; start the Docker stack to include them.

### Tests and coverage

| What | How to run | Tests | Line coverage |
|---|---|---|---|
| Java and Scala (common, spark-job, workflow-app) | `mvn clean test` | 67 | about 96% |
| Angular UI | `cd ui && npx ng test --watch=false --browsers=ChromeHeadless --code-coverage` (set `CHROME_BIN` to Chrome if it is not found) | 25 | 100% |

- **Backend coverage report:** `mvn test` writes `<module>/target/site/jacoco/index.html` (and `jacoco.csv`) for each module. UI coverage is written to `ui/coverage/`.
- **Needs HBase for the full number.** Without the Docker HBase, the two real-HBase tests are skipped and `spark-job` coverage drops to about 13%, because reading and writing HBase is then not exercised.
- **Scala branch coverage looks low (about 21%).** JaCoCo counts branches that the Scala compiler generates itself; line coverage is the meaningful figure for the Scala module.
- **Not covered on purpose:** the `main` method of `WorkflowServer` (it blocks forever; its `createServer` is tested over real HTTP with a real multipart upload) and the trivial constructors of the Scala case classes.
- **If a test run crashes with `TypeNotPresentExceptionProxy`,** it is stale build output: run `mvn clean test`.

## Run locally with Docker

All commands are run from the project root. Use Git Bash; in PowerShell replace `$(pwd -W)` with `${PWD}`.

**1. Start HBase** (ZooKeeper included; the first start takes about 20 seconds):

    docker network create sbnet
    docker run -d --name hbase --hostname hbase --network sbnet -p 16010:16010 harisekhon/hbase:1.4

HBase web UI: http://localhost:16010

**2. Build the runtime image** (Java 8 + Spark 2.4.8, downloads about 230 MB once):

    docker build -t salesbatch-runtime docker

**3. Start the app.** The project folder is mounted at `/work`:

    MSYS_NO_PATHCONV=1 docker run -d --name salesbatch-app --network sbnet -p 8080:8080 \
      -v "$(pwd -W):/work" salesbatch-runtime \
      java -Dserver.port=8080 -Dspark.master=local[*] -Dspark.hbase.site= \
      -Dreport.dir=/work/reports \
      -Dspark.job.jar=/work/spark-job/target/spark-job-1.0.0-SNAPSHOT.jar \
      -cp "/conf:/work/workflow-app/target/workflow-app.jar" \
      com.example.salesbatch.workflow.WorkflowServer

On startup the app creates the HBase tables `sales_raw`, `sales_agg` and `batch_audit` if they are missing. Check with `docker logs salesbatch-app`.

**Stop / restart**

    docker rm -f salesbatch-app hbase        # stop and remove both
    docker restart salesbatch-app            # restart the app only (Activiti process IDs reset)

After a rebuild (`mvn clean package`), run `docker restart salesbatch-app` to pick up the new jar.

## Use the API

### Swagger UI

Open **http://localhost:8080/** (redirects to `/docs/index.html`). Expand an endpoint, click **Try it out**, then **Execute**.

The OpenAPI spec is hand-written in `workflow-app/src/main/resources/swagger/openapi.json`. Update it when you add or change an endpoint.

### Endpoints

| Call | Purpose | Responses |
|---|---|---|
| `POST /batches` body `{"csvPath":"...","businessDate":"yyyyMMdd"}` | Start a run. Returns at once; ingest and the Spark job run in the background. | 202 `{"processInstanceId","status":"QUEUED"}`, 400 bad date. A bad CSV or a failed Spark job ends the run as `FAILED` (see `GET /batches/{id}`) |
| `POST /batches/upload` multipart form: `file` (the CSV) and `businessDate` | Same as `POST /batches`, but the CSV is uploaded instead of read from a server path. Used by the Angular UI | same as `POST /batches`; 400 also for an empty file |
| `GET /data/raw?date=yyyyMMdd` | Uploaded rows read from HBase (`sales_raw`) | 200 list, 400 bad date |
| `GET /data/agg?date=yyyyMMdd` | Spark aggregates read from HBase (`sales_agg`) | 200 list, 400 bad date |
| `GET /batches/{id}` | Status of a run. Poll it after starting | 200 `QUEUED` / `RUNNING` / `WAITING_APPROVAL` / `COMPLETED` / `REJECTED` / `FAILED` (with an `error` field), 404 unknown id |
| `POST /batches/{id}/approve?approved=true&approver=alice` | Approve (`true`) or reject (`false`) | 200 new status, 409 not waiting for approval (still running, unknown, or already decided) |

### CSV format

Header row, then one row per order:

    orderId,productId,region,qty,amount
    A1,P1,EU,2,10.50

`csvPath` is the path **as the app sees it**. In the Docker setup the project folder is `/work`, so `sample-data\sales-20261113.csv` is `/work/sample-data/sales-20261113.csv`.

### Example with curl

    curl -X POST localhost:8080/batches -H 'Content-Type: application/json' \
         -d '{"csvPath":"/work/sample-data/sales-20261113.csv","businessDate":"20261113"}'
    curl localhost:8080/batches/<processInstanceId>                                    # WAITING_APPROVAL
    curl -X POST "localhost:8080/batches/<id>/approve?approved=true&approver=alice"    # COMPLETED
    cat reports/report-20261113.csv

Expected report for the sample data:

    date,region,productId,totalQty,totalAmount,orderCount
    20261113,EU,P1,5,26.25,2
    20261113,US,P1,5,30.00,1
    20261113,US,P2,1,4.00,1

## Run on the EMR master node (emr-5.36 with Spark and HBase)

    java -Dserver.port=8080 \
         -Dspark.job.jar=/home/hadoop/spark-job-1.0.0-SNAPSHOT.jar \
         -cp "/etc/hbase/conf:workflow-app.jar" com.example.salesbatch.workflow.WorkflowServer

`/etc/hbase/conf` on the classpath lets the app find ZooKeeper. Copy both jars to the node first. Reports go to `/tmp/sales-batch-reports` unless you set `-Dreport.dir`.

## Debug in the IDE (VS Code) with real HBase and Spark

Everything runs on your machine and you can set breakpoints in Java and Scala:

```
Angular UI (ng serve, :4300) --proxy--> Java app (VS Code debugger, :8081)
                                           |-- HBase (Docker container sales-hbase, :2181)
                                           '-- Scala Spark job (child JVM, local[*], debug port :5006)
```

- The Java app runs under the VS Code Java debugger on **JDK 8**.
- HBase runs in Docker (the only Docker dependency).
- The Spark job runs as a local child JVM with `spark.master=local[*]`, so no `spark-submit` or Spark install is needed. Its debug port is opened with `-Dspark.debug.port=5006`, and the Scala (Metals) debugger attaches to it.

### Prerequisites

| Tool | Version / note |
|---|---|
| JDK | **8 only** (Temurin 8 tested). Set `JAVA_HOME` to it |
| Maven | 3.6+ (3.9.9 tested), internet access to Maven Central |
| Docker | Rancher Desktop or Docker Desktop, daemon running |
| Node.js | 20.19+ (24.14 tested); Angular 20 is used |
| VS Code extensions | *Extension Pack for Java* (Red Hat Java, Debugger for Java, Test Runner, Maven) and *Scala (Metals)* (`scalameta.metals`) |

### One-time setup

All commands run from the project root. Use Git Bash.

**1. Check the VS Code config in `.vscode/`** (`setup-local.cmd` writes `settings.json` for your machine; `launch.json` uses `JAVA_HOME`):

| File and key | What it does |
|---|---|
| `settings.json` `java.configuration.runtimes` | Makes VS Code compile and run the project on JDK 8. Set `path` to your JDK 8 folder |
| `settings.json` `metals.javaHome` | The Metals server itself needs Java 17+, which JDK 8 cannot provide. This points Metals at the Java runtime bundled with the Red Hat Java extension. It affects only Metals, not your builds. If the extension updates, the folder name (`25.0.4.1-win32-x86_64`) changes, so adjust it |
| `settings.json` `debug.allowBreakpointsEverywhere` | Lets you place breakpoints in `.scala` files |
| `launch.json` `javaExec` | Forces the app to start on JDK 8: `${env:JAVA_HOME}/bin/java.exe`. VS Code must be started after `JAVA_HOME` is set |
| `launch.json` `console: internalConsole` | Starts the JVM directly, not through PowerShell. This avoids `TransportTimeoutException` and problems with spaces in the folder path |

**2. Start the local stack (HBase, plus a small Spark cluster you can use optionally):**

    docker build -t salesbatch-runtime docker                       # once: Java 8 + Spark 2.4.8 image
    docker compose -f docker/local/docker-compose.yml up -d

This starts HBase (ZooKeeper 2181, master 16000, region server 16020, web UI 16011) and a Spark standalone cluster (1 master, 2 workers; master UI http://localhost:8090). HBase takes about a minute to be ready. It advertises the name `host.docker.internal`, which Docker Desktop and Rancher Desktop add to your Windows hosts file (127.0.0.1) and which containers resolve to the host. So the IDE and the Spark containers reach the same HBase and no hosts-file edit is needed. The app reads its HBase address from `workflow-app/src/test/resources/hbase-site.xml`. Stop with `docker compose -f docker/local/docker-compose.yml down` (this also deletes the HBase data).

**3. Build, and write the classpath the Scala job runs with:**

    mvn -pl spark-job -am compile dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt

Repeat this after changing Scala code or after `mvn clean`. The Scala job loads `common/target/classes` and `spark-job/target/classes` from disk.

**4. Install the Angular dependencies:**

    cd ui && npm install

**5. Let Metals build the Scala project** (needed only for Scala breakpoints):

1. Open the project in VS Code. When Metals asks to import the build, accept; otherwise generate it by hand:
   `mvn ch.epfl.scala:maven-bloop_2.13:1.5.6-35-f736b2a5:bloopInstall`. This creates `.bloop/`.
2. Run `node scripts/fix-bloop.js`. Metals compiles the Java module `common` with a `-release 8` flag that JDK 8 rejects, and that breaks the Scala attach with "Compilation not successful". The script removes that dependency; `common` is already built by Maven.
3. In VS Code run **Metals: Restart build server** (Ctrl+Shift+P). Run the script again after every re-import.

### Every time you debug

1. Make sure the local stack is running: `docker compose -f docker/local/docker-compose.yml up -d` (wait about a minute for HBase).
2. Start the UI: `cd ui && npx ng serve`, then open http://localhost:4300. The UI is on 4300 because 4200 is often taken.
3. In VS Code, Run and Debug (Ctrl+Shift+D), choose a launch config, press F5. Wait for `Local debug server on http://localhost:8081/` in the Debug Console.
4. Upload a sample file in the UI (see below).

| Launch config | Use it for |
|---|---|
| **Debug app (real HBase + Scala Spark job)** | Java breakpoints. The Spark job runs without waiting for a debugger |
| **Debug app + wait for Scala debugger on 5006** | Java and Scala breakpoints. The Spark child JVM pauses until you attach |
| **Attach to Scala Spark job (5006)** | Start it after the upload reaches the Spark step, to hit Scala breakpoints (needs step 5 above) |
| **Debug app (in-memory fakes, no Docker)** | No HBase or Spark at all; quick check of the REST and workflow code |
| **Debug app (Postgres + Docker Spark cluster)** | The Spark job runs on the Docker cluster (driver in `spark-master`, executors on the 2 workers) and the workflow state lives in Postgres. See "Optional: Docker Spark cluster and Postgres" |
| **Debug app (Postgres + Docker Spark cluster) + wait for Scala debugger on 5006** | Same, and the Spark driver pauses until you attach to port 5006 |

### Debugging both Java and Scala in one run

1. Set breakpoints. Java: `BatchController.upload`, `BatchService.start`, `IngestDelegate.execute`, `HBaseSalesRepository.saveRaw`, `SparkJobDelegate.execute`, `ProcessSparkJobLauncher.run`, `DataController.raw` and `agg`. Scala (`SalesAggregator.scala`): `main`, `readRaw`, `aggregate`, `writeAgg`.
2. Start **Debug app + wait for Scala debugger on 5006**.
3. In the UI choose `sample-data/sales-20261115.csv`, date `20261115`, click **Upload & start**. The request returns at once (the status shows `QUEUED`, then `RUNNING`). `BatchController.upload` and `BatchService.start` hit on the request thread. `IngestDelegate`, `SparkJobDelegate` and the launcher hit later on a background thread (`activiti-job-executor`); continue (F5) to the Spark step.
4. The status stays `RUNNING`: the Spark child JVM is paused on port 5006. Start **Attach to Scala Spark job (5006)**. The Scala breakpoints hit; continue to let the job finish.
5. The status becomes `WAITING_APPROVAL` and the "Data in HBase" tables fill in. Click **Approve** and check `reports/report-20261115.csv`.

Use the Call Stack panel's session dropdown to switch between the Java and Scala sessions.

### Sample files

| File | Use | Expected result |
|---|---|---|
| `sample-data/sales-20261115.csv` | Main test: 12 orders, 3 regions, 4 products. Date `20261115` | 12 raw rows, 9 aggregate rows, `reports/report-20261115.csv` after approval |
| `sample-data/sales-20261113.csv` | Small file, 4 orders. Date `20261113` | 4 raw rows, 3 aggregate rows |
| `sample-data/sales-bad.csv` | Error path: line 3 has a bad quantity | Error `Line 3: bad number ...`, nothing is saved |

Expected aggregates for `sales-20261115.csv` (region, product, total qty, total amount, orders):
`APAC P1 6 36.00 1`, `APAC P2 3 12.00 1`, `APAC P4 3 38.97 2`, `EU P1 5 26.25 2`, `EU P2 1 4.00 1`, `EU P3 4 22.00 1`, `US P1 5 30.00 1`, `US P2 3 12.00 2`, `US P3 3 16.50 1`.

You can also inspect the data directly: Swagger at http://localhost:8081/, the HBase web UI at http://localhost:16011, or `docker exec -it sales-hbase hbase shell` (`scan 'sales_raw'`, `scan 'sales_agg'`).

### Optional: Docker Spark cluster and Postgres

By default the Spark job runs as a child JVM with `local[*]` (easiest to debug). Two optional modes behave more like production:

- **Spark on a real (standalone) cluster in Docker.** `-Dspark.mode=docker-cluster` makes the app run `docker exec spark-master spark-submit ...` in client deploy mode. The driver runs in the `spark-master` container and the executors on the two workers, so the code and data really cross process boundaries. This is the mode that exposes serialization and distribution problems that `local[*]` hides. Check the master UI at http://localhost:8090 for the running and completed applications.
- **Workflow state in Postgres.** Activiti keeps its runs and pending background jobs in a database. Create an isolated database in your existing Postgres once (the superuser is `postgres`):

        docker exec postgres psql -U postgres -c "CREATE ROLE salesbatch LOGIN PASSWORD salesbatch" -c "CREATE DATABASE salesbatch_activiti OWNER salesbatch"

Both are switched on together by the launch configs **Debug app (Postgres + Docker Spark cluster)**. They pass `-Duser.timezone=UTC` because Java 8 reports the old zone name `Asia/Calcutta`, which Postgres 17 rejects.

Notes for this mode:
- The cluster reads the **fat jar** `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` through the `/work` mount, not `target/classes`. After changing Scala code run `mvn -pl spark-job -am package -DskipTests`.
- **Scala debugging:** use the "... + wait for Scala debugger on 5006" config, then **Attach to Scala Spark job (5006)** as usual. Port 5006 is published from the `spark-master` container, where the **driver** runs, so breakpoints in `main`, `readRaw` and `writeAgg` setup code hit. Code inside Spark tasks (the `map` bodies, `toRawSale`, the `foreachPartition` body) runs in the **worker JVMs** and is not reachable through port 5006. To debug those, use the default `local[*]` mode.
- Spark executors use 512 MB each and the driver runs inside the master container, so the first run after `docker compose up` is slower.

### Troubleshooting the local debug setup

| Symptom | Cause and fix |
|---|---|
| Vite `http proxy error ... ECONNREFUSED` in the `ng serve` output | The Java app is not running on 8081. Start a launch config and wait for `Local debug server ...` |
| `Could not find or load main class RiskCanvas\...` | A `-D` option contained a path with a space (the folder is "Genpact RiskCanvas") and was split. Do not put `${workspaceFolder}` paths in `vmArgs` |
| `TransportTimeoutException` popup | The JVM was never started through the terminal. Keep `"console": "internalConsole"` in `launch.json` |
| `InaccessibleObjectException` / cglib errors at startup | The app is running on Java 9+. Keep `javaExec` and `java.configuration.runtimes` pointing at JDK 8 |
| UI shows another app ("Orders") or port in use | Port 4200 belongs to another project. This UI uses 4300 (`ui/angular.json`) |
| Status stays `RUNNING` for a long time | You started the "wait for Scala debugger" config and have not attached, or you are stopped at a breakpoint in a background thread. Attach to 5006 or continue; or use the plain config |
| `Missing spark-job/target/local-classpath.txt` | Run step 3 of the setup |
| App fails at startup with an HBase error | The container is not ready or not running: `docker ps`, wait a minute after starting; ports 2181, 16000 and 16020 must be published |
| Scala breakpoints grey, or `no build target found` in the Metals log | The Metals import has not happened. Do step 5 of the setup |
| Scala attach fails with "Internal error" and the Metals log says `Compilation not successful` / `bad option: '-release'` | Run `node scripts/fix-bloop.js`, then **Metals: Restart build server** |
| Scala attach is refused | The Spark child JVM only exists while a run is at the Spark step (status `RUNNING`). Upload first, then attach |
| `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"` at startup | Add `-Duser.timezone=UTC` to `vmArgs` (the Postgres launch configs already have it) |
| Run is `FAILED` in Docker cluster mode with a connection error to `host.docker.internal` | HBase is not up, or the container cannot resolve the name. Check `docker ps`, and that `docker run --rm --entrypoint getent salesbatch-runtime hosts host.docker.internal` prints an address |
| Docker cluster mode: `No such container: spark-master` | Run `docker compose -f docker/local/docker-compose.yml up -d` |
| Everything breaks after `mvn clean` | Rebuild: setup step 3 (this also recreates `common/target/classes`, which the Scala build uses) |

## Configuration

Defaults are in `workflow-app/src/main/resources/application.properties`; override any of them with `-D`.

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `report.dir` | `/tmp/sales-batch-reports` | Where reports are written |
| `spark.submit.command` | `spark-submit` | Spark launcher on the PATH |
| `spark.master` | `yarn` | Use `local[*]` for local runs |
| `spark.deploy.mode` | `cluster` | Ignored when the master is `local` |
| `spark.job.jar` | `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` | Path of the Spark fat jar |
| `spark.hbase.site` | `/etc/hbase/conf/hbase-site.xml` | Passed to `--files`; leave empty for local runs |

## Notes and limitations

- The Spark job bundles the HBase client and relocates Guava, because EMR does not put HBase on Spark's classpath. Spark and Hadoop are `provided`.
- In the Docker image, `hbase-site.xml` (ZooKeeper quorum `hbase`) is placed in Spark's `conf` directory so the Spark job can find HBase.
- Runs are asynchronous: `POST /batches` returns `202` and the Ingest and Spark steps run in Activiti's background job executor (`activiti:async` in the BPMN, `setJobExecutorActivate(true)`). A failing step does not throw; it records the reason and the run ends `FAILED`.
- Activiti uses in-memory H2 by default (see `EngineConfig`): run IDs and pending runs are lost on restart. The IDE debug server (`LocalDebugServer`) stores them in `./data/activiti` instead, so a run waiting for approval can still be approved after a restart. Delete the `data` folder to reset it. Set `-Dactiviti.jdbc.url=...` to choose another database. The HBase `batch_audit` table keeps run status and the failure reason either way.
- Raw rows stay in HBase after a run. Use a new `businessDate` for each test; re-running the same date has not been verified.
- Local runs use Spark in `local[*]` mode. Behaviour on YARN or EMR has not been tested.

## Troubleshooting

- **`mvn` fails with a jansi `NoSuchMethodError` in `scala-maven-plugin`:** use plugin version 4.8.1 or later (already set in the root `pom.xml`).
- **App fails at startup with HBase connection errors:** make sure the `hbase` container is running and on the `sbnet` network, and give it about 20 seconds after the first start.
- **Browser shows 404 at `/batches`:** that is expected; use `/` for Swagger UI or `GET /batches/{id}` with a real id.
- **`docker run -v` fails on Windows paths in Git Bash:** keep `MSYS_NO_PATHCONV=1` and `$(pwd -W)` as shown above.
