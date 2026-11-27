# Sales Batch

A sales-batch workflow application. You upload a sales CSV, and the app:

1. loads it into **HBase 1.4**,
2. runs a **Spark 2.4 (Scala 2.11)** job that aggregates it,
3. waits for a **manager approval**,
4. writes `report-<businessDate>.csv`.

| Layer | Technology |
|---|---|
| API and workflow | Java 8, Spring MVC 4.3, Activiti 5.22, embedded Jetty |
| Batch job | Scala 2.11 on Spark 2.4.8 (target runtime: EMR 5.36) |
| Storage | HBase 1.4 (runs in Docker) |
| UI | Angular 20 |

This README gets you from a clean machine to a running, debuggable system in VS Code.
For design details see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Contents

1. [How the pieces fit together](#1-how-the-pieces-fit-together)
2. [What to install](#2-what-to-install)
3. [One-time setup](#3-one-time-setup)
4. [Run the project](#4-run-the-project)
5. [Try it: upload a CSV](#5-try-it-upload-a-csv)
6. [Debug in VS Code](#6-debug-in-vs-code)
7. [Build and test](#7-build-and-test)
8. [API reference](#8-api-reference)
9. [Configuration](#9-configuration)
10. [Optional: Postgres for workflow state](#10-optional-postgres-for-workflow-state)
11. [Troubleshooting](#11-troubleshooting)
12. [Project layout](#12-project-layout)

---

## 1. How the pieces fit together

```
Browser ──> Angular UI (ng serve, :4300)
                │  proxy: /batches and /data
                ▼
            Java API (:8081)  ── runs on your machine, started from VS Code
                │
                ├── HBase  (Docker container sales-hbase, :2181)
                └── Spark job
                      ├─ local[*]       child JVM on your machine  (easiest to debug)
                      └─ docker-cluster spark-master + 2 workers in Docker
```

- **In Docker:** HBase, plus a small Spark cluster (1 master, 2 workers).
- **On your machine:** the Java API, the Scala job (when run as a child JVM) and the Angular UI. This lets you set breakpoints in all three.

> **The UI needs the Java API.** The UI only proxies requests to `localhost:8081`. If the API is not running, uploads fail with **500 Internal Server Error** and `ng serve` prints `http proxy error ... ECONNREFUSED`. Always start the API (section 4) before using the UI.

---

## 2. What to install

Windows 10/11 is the tested platform. macOS and Linux notes are at the end of this section.

You need about **6 GB of free RAM**, **10 GB of disk** and internet access for the first build.

### 2.1 Required software

| # | Tool | Version | Download | Notes |
|---|---|---|---|---|
| 1 | **JDK 8** | Temurin 8 (8u4xx) | https://adoptium.net/temurin/releases/?version=8 | **Must be 8.** Newer JDKs break this dependency set. Choose the `.msi` installer and tick **Set JAVA_HOME variable** and **Add to PATH** |
| 2 | **Apache Maven** | 3.6 or newer (3.9.9 tested) | https://maven.apache.org/download.cgi | Unzip, then add its `bin` folder to `PATH` |
| 3 | **Node.js** | **20.19 or newer** (24.14 tested) | https://nodejs.org (LTS) | Angular 20 does not work on Node 12 to 18. Check with `node -v` |
| 4 | **Docker** | Docker Desktop or Rancher Desktop | https://www.docker.com/products/docker-desktop/ | Must be **running**. Allow about 4 GB of memory for it |
| 5 | **Visual Studio Code** | latest | https://code.visualstudio.com | |
| 6 | **Git** (optional) | any | https://git-scm.com | Gives you Git Bash; useful for the commands below |

Optional:

| Tool | Why |
|---|---|
| **JDK 17** (Temurin 17) | A robust Java runtime for the Scala (Metals) tooling, see 2.3 |
| **Google Chrome** | Only to run the UI unit tests |
| **nvm for Windows** (https://github.com/coreybutler/nvm-windows) | To keep several Node versions side by side |

### 2.2 Set the environment and check it

Open a **new** Command Prompt or PowerShell (so it sees the new variables) and check:

```
java -version        -> must print  1.8.0_...
echo %JAVA_HOME%     -> the JDK 8 folder, for example C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot
mvn -v               -> Apache Maven 3.6 or newer (and "Java version: 1.8...")
node -v              -> v20.19 or newer
npm -v
docker info          -> prints server information (no "cannot connect" error)
```

If you have several JDKs, set `JAVA_HOME` to JDK 8 and put `%JAVA_HOME%\bin` first on `PATH`. If you have several Node versions (nvm): `nvm use 24.14.0`. nvm applies only to the current terminal, so do it again in each new one.

### 2.3 VS Code extensions

Open the project folder in VS Code and accept the prompt **Install recommended extensions** (the list is in `.vscode/extensions.json`). Or install them by hand:

| Extension | ID | Needed for |
|---|---|---|
| **Extension Pack for Java** | `vscjava.vscode-java-pack` | Java language support, debugger, Maven, test runner (it installs Language Support for Java by Red Hat, Debugger for Java, Maven for Java, Test Runner for Java, Project Manager for Java) |
| **Scala (Metals)** | `scalameta.metals` | Scala code and **Scala breakpoints** for the Spark job |
| **Angular Language Service** | `angular.ng-template` | UI editing (optional) |
| **Docker** | `ms-azuretools.vscode-docker` | Viewing containers and logs (optional) |

Install from a terminal:

```
code --install-extension vscjava.vscode-java-pack
code --install-extension scalameta.metals
code --install-extension angular.ng-template
code --install-extension ms-azuretools.vscode-docker
```

**Metals needs Java 17 or newer to run itself**, while the project needs JDK 8. The two are separate:

- `java.configuration.runtimes` in `.vscode/settings.json` points VS Code builds and runs at **JDK 8**.
- `metals.javaHome` in the same file points the Metals server at a Java **17+**. `setup-local.cmd` fills it in with the runtime bundled in the Red Hat Java extension. If that folder is missing or the extension updates and the folder name changes, install Temurin 17 and set `metals.javaHome` to its folder instead.

### 2.4 Other platforms (not tested)

The project code is cross-platform, but the helper scripts and `launch.json` are written for Windows. On macOS or Linux:

- Install the same tools (JDK 8, Maven, Node 20.19+, Docker, VS Code) with your package manager.
- Skip `setup-local.cmd` and run its steps by hand (see 3.2).
- Create `.vscode/settings.json` yourself (see 3.3).
- `host.docker.internal` must resolve to the host. Docker Desktop does this on macOS; on Linux add `127.0.0.1 host.docker.internal` to `/etc/hosts`.

---

## 3. One-time setup

### 3.1 Get the project

Copy or unzip the project into a folder. A path **without spaces** is best.

### 3.2 Run the setup script (Windows)

From the project folder, in a Command Prompt (or double-click the file):

```
setup-local.cmd
```

It does six things and stops with a clear message if one fails:

| Step | What it does |
|---|---|
| 1 | Checks JDK 8, Maven, Node, Docker (and that Docker is running) |
| 2 | Builds the Docker image `salesbatch-runtime` (Java 8 + Spark 2.4.8; the first build downloads about 230 MB) |
| 3 | `mvn package` for the Java and Scala code, and writes `target/local-classpath.txt` |
| 4 | `npm ci` in `ui/` |
| 5 | Writes `.vscode/settings.json` for your machine (skipped if the file already exists) |
| 6 | Starts HBase and the Spark cluster in Docker |

The first run takes several minutes. When it finishes, give HBase about a minute to be ready.

Manual equivalent (macOS, Linux, or if you prefer typing):

```
docker build -t salesbatch-runtime docker
mvn -B -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt
cd ui && npm ci && cd ..
docker compose -f docker/local/docker-compose.yml up -d
```

### 3.3 Check `.vscode/settings.json`

`setup-local.cmd` creates it from your `JAVA_HOME` (the file is git-ignored, so each developer has their own). If you write it yourself, it looks like this (use your own paths):

```json
{
  "java.configuration.updateBuildConfiguration": "automatic",
  "debug.allowBreakpointsEverywhere": true,
  "java.configuration.runtimes": [
    { "name": "JavaSE-1.8", "path": "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot", "default": true }
  ],
  "files.watcherExclude": { "**/target/**": true },
  "metals.javaHome": "C:/Program Files/Eclipse Adoptium/jdk-17.0.x-hotspot"
}
```

### 3.4 Prepare Metals (only if you want Scala breakpoints)

1. **Start VS Code from a new terminal** in the project folder (`code .`), so it sees `JAVA_HOME`.
2. When Metals asks to **import the build**, click **Import build**. If it does not ask, run `mvn ch.epfl.scala:maven-bloop_2.13:1.5.6-35-f736b2a5:bloopInstall`, which creates `.bloop/`.
3. Run:

   ```
   node scripts/fix-bloop.js
   ```

   Metals compiles the Java module `common` with a `-release 8` flag that JDK 8 rejects, which breaks the Scala debugger attach. The script removes that dependency (Maven already builds `common`).
4. In VS Code press **Ctrl+Shift+P** and run **Metals: Restart build server**.

Repeat steps 3 and 4 after every re-import of the build.

---

## 4. Run the project

You need three things running: Docker (HBase and Spark), the Java API, and the UI.

### 4.1 Start Docker services

```
docker compose -f docker/local/docker-compose.yml up -d
docker ps          # sales-hbase, spark-master, spark-worker-1, spark-worker-2
```

Wait about a minute for HBase. Web UIs: HBase http://localhost:16011, Spark master http://localhost:8090.

### 4.2 Start the Java API (port 8081)

**In VS Code (recommended, supports breakpoints):** open **Run and Debug** (Ctrl+Shift+D), choose a configuration and press **F5**. Wait for this line in the Debug Console:

```
Local debug server on http://localhost:8081/
```

Which configuration:

| Launch configuration | Use it for |
|---|---|
| **Debug app (real HBase + Scala Spark job)** | The default. Java breakpoints. The Spark job runs as a child JVM (`local[*]`) |
| **Debug app (HBase + Docker Spark cluster)** | The Spark job runs on the Docker cluster, like production |
| **Debug app + wait for Scala debugger on 5006** | Java and Scala breakpoints; the Spark JVM pauses until you attach |
| **Attach to Scala Spark job (5006)** | Start after an upload reaches the Spark step, to hit Scala breakpoints |
| **Debug app (in-memory fakes, no Docker)** | Quick check of REST and workflow code; no HBase or Spark at all |
| **Debug app (Postgres + Docker Spark cluster)** | Workflow state in Postgres, see section 10 |

**From a terminal (no debugger).** Build first (step 3 of the setup), then, from the project root:

```bat
:: Windows Command Prompt
set /p CP=<workflow-app\target\local-classpath.txt
"%JAVA_HOME%\bin\java.exe" -Dserver.port=8081 -Dspark.mode=docker-cluster ^
  -cp "workflow-app\target\test-classes;workflow-app\target\classes;%CP%" ^
  com.example.salesbatch.workflow.LocalDebugServer
```

`LocalDebugServer` lives in the test sources, so `test-classes` must be on the classpath. In PowerShell, put each `-D...` argument in single quotes, or PowerShell splits it at the dot.

> `-Dspark.mode=docker-cluster` needs the Docker stack from 4.1. Drop that option to run Spark as a child JVM instead.

### 4.3 Start the UI

```
cd ui
npm start
```

Open **http://localhost:4300**. (Port 4300 is used because 4200 is often taken; it is set in `ui/angular.json`.) Use Node 20.19 or newer in this terminal.

### 4.4 Stop everything

```
docker compose -f docker/local/docker-compose.yml down     # also deletes the HBase data
```

Stop the API with the red square in VS Code, and the UI with **Ctrl+C**.

Next time, you only repeat 4.1 to 4.3. Nothing needs installing again.

---

## 5. Try it: upload a CSV

1. In the UI choose `sample-data/sales-20261115.csv`, enter the date `20261115`, click **Upload & start**.
2. The status goes `QUEUED` → `RUNNING` → `WAITING_APPROVAL`.
3. The "Data in HBase" tables fill in. Click **Approve**.
4. The status becomes `COMPLETED` and the report is written to `reports/report-20261115.csv`.

| Sample file | Date | Expected result |
|---|---|---|
| `sample-data/sales-20261115.csv` | `20261115` | 12 raw rows, 9 aggregate rows, a report after approval |
| `sample-data/sales-20261113.csv` | `20261113` | 4 raw rows, 3 aggregate rows |
| `sample-data/sales-bad.csv` | any | Error path: `Line 3: bad number ...`, nothing is saved |

Expected aggregates for `sales-20261115.csv` (region, product, total qty, total amount, orders):

```
APAC P1 6 36.00 1    APAC P2 3 12.00 1    APAC P4 3 38.97 2
EU   P1 5 26.25 2    EU   P2 1  4.00 1    EU   P3 4 22.00 1
US   P1 5 30.00 1    US   P2 3 12.00 2    US   P3 3 16.50 1
```

Use a **new business date for each test**; re-running the same date has not been verified.

You can also use Swagger at http://localhost:8081/ or inspect HBase with `docker exec -it sales-hbase hbase shell` (`scan 'sales_raw'`, `scan 'sales_agg'`).

---

## 6. Debug in VS Code

### Java

Start any Java launch configuration (4.2) and set breakpoints. Useful places: `BatchController.upload`, `BatchService.start`, `IngestDelegate.execute`, `HBaseSalesRepository.saveRaw`, `SparkJobDelegate.execute`, `ProcessSparkJobLauncher.run`, `DataController.raw` and `agg`.

`BatchController.upload` and `BatchService.start` hit on the request thread. `IngestDelegate`, `SparkJobDelegate` and the launcher run later on a background thread (`activiti-job-executor`); press F5 to continue to the next step.

### Java and Scala in one run

1. Complete 3.4 (Metals) once.
2. Set Scala breakpoints in `SalesAggregator.scala` (`main`, `readRaw`, `aggregate`, `writeAgg`).
3. Start **Debug app + wait for Scala debugger on 5006**.
4. Upload a file in the UI. The status stays `RUNNING`: the Spark JVM is paused, waiting on port 5006.
5. Start **Attach to Scala Spark job (5006)**. The Scala breakpoints hit. Continue to let the job finish.
6. Use the session dropdown in the Call Stack panel to switch between the Java and Scala sessions.

### Docker cluster mode

With `-Dspark.mode=docker-cluster` the driver runs in the `spark-master` container and the executors on the two workers, so serialization and distribution problems show up that `local[*]` hides.

- The cluster reads the **fat jar** `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar`. After changing Scala code run `mvn -pl spark-job -am package -DskipTests`.
- Port 5006 is published from `spark-master`, where the driver runs. Breakpoints in `main`, `readRaw` and `writeAgg` setup code hit. Code inside Spark tasks (`map` bodies, `foreachPartition`) runs in the worker JVMs and is **not** reachable that way; use `local[*]` for those.

---

## 7. Build and test

```
mvn clean package         # builds all modules and runs the tests
```

Produces `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` (about 29 MB) and `workflow-app/target/workflow-app.jar` (about 55 MB).

| What | Command | Tests |
|---|---|---|
| Java and Scala (`common`, `spark-job`, `workflow-app`) | `mvn clean test` | 67 |
| Angular UI | `cd ui && npx ng test --watch=false --browsers=ChromeHeadless --code-coverage` | 25 |

- Coverage: `<module>/target/site/jacoco/index.html` for the backend, `ui/coverage/` for the UI.
- Two tests in `spark-job` (`SalesAggregatorHBaseTest`) use the real HBase on `localhost` and are **skipped automatically** when HBase is not running. Start the Docker stack to include them.
- UI tests need Chrome. Set `CHROME_BIN` if it is not found.
- If a run crashes with `TypeNotPresentExceptionProxy`, it is stale output: run `mvn clean test`.
- After `mvn clean`, rebuild the classpath file: `mvn -pl spark-job -am compile dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt`.

---

## 8. API reference

Swagger UI: **http://localhost:8081/** (redirects to `/docs/index.html`). The spec is `workflow-app/src/main/resources/swagger/openapi.json`; update it when you change an endpoint.

### Flow

```
POST /batches  ->  202 QUEUED at once; then in the background:
                   RUNNING: load CSV into HBase (sales_raw) -> Spark aggregates into sales_agg
                   -> WAITING_APPROVAL   (or FAILED, with an error message)
GET  /batches/{id}                       -> poll until WAITING_APPROVAL or FAILED
POST /batches/{id}/approve?approved=true  -> report written -> COMPLETED
POST /batches/{id}/approve?approved=false -> nothing published -> REJECTED
```

The process definition is `workflow-app/src/main/resources/processes/sales-batch.bpmn20.xml`.

### Endpoints

| Call | Purpose | Responses |
|---|---|---|
| `POST /batches` body `{"csvPath":"...","businessDate":"yyyyMMdd"}` | Start a run from a CSV path the server can read | 202 `{"processInstanceId","status":"QUEUED"}`, 400 bad date |
| `POST /batches/upload` multipart: `file`, `businessDate` | Same, but the CSV is uploaded (used by the UI) | as above; 400 also for an empty file |
| `GET /data/raw?date=yyyyMMdd` | Uploaded rows from HBase (`sales_raw`) | 200 list, 400 bad date |
| `GET /data/agg?date=yyyyMMdd` | Spark aggregates from HBase (`sales_agg`) | 200 list, 400 bad date |
| `GET /batches/{id}` | Status: `QUEUED`, `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `REJECTED`, `FAILED` (with `error`) | 200, 404 unknown id |
| `POST /batches/{id}/approve?approved=true&approver=alice` | Approve or reject | 200 new status, 409 not waiting for approval |

A browser `GET` on `/batches` gives 405 or 404. That is expected: use `/` for Swagger or `GET /batches/{id}` with a real id.

### CSV format

```
orderId,productId,region,qty,amount
A1,P1,EU,2,10.50
```

### Example with curl

```
curl -X POST localhost:8081/batches -H "Content-Type: application/json" -d "{\"csvPath\":\"sample-data/sales-20261113.csv\",\"businessDate\":\"20261113\"}"
curl localhost:8081/batches/<processInstanceId>
curl -X POST "localhost:8081/batches/<id>/approve?approved=true&approver=alice"
```

Expected report for `sales-20261113.csv`:

```
date,region,productId,totalQty,totalAmount,orderCount
20261113,EU,P1,5,26.25,2
20261113,US,P1,5,30.00,1
20261113,US,P2,1,4.00,1
```

---

## 9. Configuration

Defaults are in `workflow-app/src/main/resources/application.properties`. Override any of them with `-D` (in `vmArgs` of `launch.json`, or on the `java` command line).

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port (the VS Code configs use `8081`) |
| `report.dir` | `/tmp/sales-batch-reports` | Where reports are written |
| `spark.submit.command` | `spark-submit` | Spark launcher on the PATH |
| `spark.master` | `yarn` | `local[*]` for local runs |
| `spark.mode` | `local-jvm` | `docker-cluster` runs the job through `docker exec spark-master spark-submit` (container name: `spark.docker.container`, default `spark-master`) |
| `spark.deploy.mode` | `cluster` | Ignored when the master is `local` |
| `spark.job.jar` | `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` | Spark fat jar |
| `spark.hbase.site` | `/etc/hbase/conf/hbase-site.xml` | Passed to `--files`; empty for local runs |
| `debug.inmemory` | `false` | Debug server only: `true` uses in-memory fakes for HBase and Spark |
| `activiti.jdbc.url` | `jdbc:h2:file:./data/activiti;...` | Workflow database (debug server) |

The local debug app reads its HBase address from `workflow-app/src/test/resources/hbase-site.xml`. HBase advertises the name `host.docker.internal`, which Docker Desktop and Rancher Desktop add to your hosts file. If it does not resolve, add `127.0.0.1 host.docker.internal` to `C:\Windows\System32\drivers\etc\hosts` (as administrator).

### Running on EMR (emr-5.36 with Spark and HBase)

```
java -Dserver.port=8080 \
     -Dspark.job.jar=/home/hadoop/spark-job-1.0.0-SNAPSHOT.jar \
     -cp "/etc/hbase/conf:workflow-app.jar" com.example.salesbatch.workflow.WorkflowServer
```

Copy both jars to the node first. `/etc/hbase/conf` on the classpath lets the app find ZooKeeper.

---

## 10. Optional: Postgres for workflow state

By default Activiti keeps runs in a file database under `./data/activiti` for the debug server (in-memory H2 for the production `WorkflowServer`). To keep them in Postgres instead, create an isolated database once (any Postgres container named `postgres` with superuser `postgres`):

```
docker exec postgres psql -U postgres -c "CREATE ROLE salesbatch LOGIN PASSWORD 'salesbatch'" -c "CREATE DATABASE salesbatch_activiti OWNER salesbatch"
```

Then use the launch configuration **Debug app (Postgres + Docker Spark cluster)**. It passes `-Duser.timezone=UTC` because Java 8 reports the old zone name `Asia/Calcutta`, which Postgres 17 rejects. Delete the `data` folder to reset the file database.

---

## 11. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| **500 on CSV upload**, and `ng serve` shows `http proxy error ... ECONNREFUSED` | The Java API is not running on 8081. Start it (4.2) and wait for `Local debug server ...` |
| `npm ci` fails with `Cannot read property '@angular/common' of undefined` | Node is too old (12 to 18). Install Node 20.19+; with nvm: `nvm use 24.14.0`, then rerun |
| `JAVA_HOME ... is not JDK 8` | Install Temurin 8, set `JAVA_HOME` to it, open a **new** terminal |
| `Could not find or load main class .port=8081` | PowerShell split a `-D` option at the dot. Quote it: `'-Dserver.port=8081'` |
| `Could not find or load main class ...LocalDebugServer` | `workflow-app\target\test-classes` is missing from the classpath. Run the build (3.2) |
| `Could not find or load main class RiskCanvas\...` | A `-D` option contained a path with a space and was split. Do not put `${workspaceFolder}` paths in `vmArgs` |
| `Missing spark-job/target/local-classpath.txt` | Run the `mvn ... dependency:build-classpath` command (3.2) |
| `TransportTimeoutException` popup | Keep `"console": "internalConsole"` in `launch.json` |
| `InaccessibleObjectException` or cglib errors at startup | The app is running on Java 9+. Keep `javaExec` and `java.configuration.runtimes` pointing at JDK 8, and start VS Code after setting `JAVA_HOME` |
| UI shows another app, or the port is in use | The UI uses port 4300 (`ui/angular.json`) |
| API fails at startup with an HBase error | HBase is not ready or not running. `docker ps`, wait a minute; ports 2181, 16000, 16020 must be published |
| Run is `FAILED` with a connection error to `host.docker.internal` | HBase is down, or the name does not resolve. See the hosts-file note in section 9 |
| `No such container: spark-master` | `docker compose -f docker/local/docker-compose.yml up -d` |
| Status stays `RUNNING` for a long time | You used a "wait for Scala debugger" config and have not attached, or you are stopped at a breakpoint. Attach to 5006, continue, or use the plain config |
| Scala breakpoints grey, or `no build target found` in the Metals log | The Metals import has not happened. Do 3.4 |
| Scala attach fails: `Compilation not successful` or `bad option: '-release'` | `node scripts/fix-bloop.js`, then **Metals: Restart build server** |
| Scala attach is refused | The Spark JVM exists only while a run is at the Spark step (`RUNNING`). Upload first, then attach |
| Metals does not start | `metals.javaHome` must point to Java 17+. See 2.3 |
| `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"` | Add `-Duser.timezone=UTC` to `vmArgs` |
| `mvn` fails with a jansi `NoSuchMethodError` | Use `scala-maven-plugin` 4.8.1 or newer (already set in the root `pom.xml`) |
| Everything breaks after `mvn clean` | Rebuild: the `mvn ... package dependency:build-classpath` command in 3.2 |

---

## 12. Project layout

| Path | What it is |
|---|---|
| `common/` | HBase access layer (`SalesRepository`, row keys, schema) |
| `spark-job/` | Scala Spark job, built as a fat jar |
| `workflow-app/` | Spring MVC + Activiti on embedded Jetty: REST API, Swagger UI, process definition. `LocalDebugServer` (in `src/test`) is the entry point used for local debugging |
| `ui/` | Angular 20 front end (`proxy.conf.json` forwards `/batches` and `/data` to `localhost:8081`) |
| `docker/` | `Dockerfile` (Java 8 + Spark 2.4.8), and `local/docker-compose.yml` for HBase and the Spark cluster |
| `sample-data/` | Example CSV files |
| `scripts/` | `fix-bloop.js`, `write-vscode-settings.ps1` |
| `.vscode/` | `launch.json`, `extensions.json`, and `settings.json` (machine specific, written by setup) |
| `reports/`, `data/` | Generated: published reports, and the local Activiti database |
| `setup-local.cmd` | One-time Windows setup |
| `docs/` | Extra documentation; `README.previous.md` is the earlier README |

Notes:

- The Spark job bundles the HBase client and relocates Guava, because EMR does not put HBase on Spark's classpath. Spark and Hadoop are `provided`.
- Runs are asynchronous. A failing step does not throw; it records the reason and the run ends `FAILED`. The HBase table `batch_audit` keeps each run's status and failure reason.
- Behaviour on YARN or EMR has not been tested; local runs use `local[*]` or the Docker standalone cluster.
