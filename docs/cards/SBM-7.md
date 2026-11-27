# SBM-7 Replace log4j 1.2 and fix critical dependency vulnerabilities

Card: SBM-7 on Relay (project SBM). Branch: `card/SBM-7` (from main at 5a91d26). Source risk: GitHub issue #3.

## Assess, the card lead, 2026-11-27

### Exit criteria

| Criterion | How it holds |
|---|---|
| The card's branch exists and is linked from the card | `card/SBM-7` pushed to origin, linked on the card (branch link). |
| The application's folder and its own test command are on the card | Below, "Change map". |
| How many tests pass before the change | Below, "Baseline": 65 pass, 2 skipped, 0 fail (Java); main's pipeline green. |
| One slice a person can try, or a person agreed its slices | One slice: about 20 lines of build-file change in two or three poms plus tests. See "Slice". |

### Change map

- Repository root: `C:\Work\javapoc1.8` (Maven reactor `pom.xml`, modules `common`, `spark-job`, `workflow-app`; `ui/` is Angular and untouched by this card).
- Files the change lands in: `common/pom.xml` (exclusions on `hbase-client`), `workflow-app/pom.xml` (add `org.slf4j:log4j-over-slf4j:${slf4j.version}`), `spark-job/pom.xml` (exclusions on `common` and `hbase-server`, if Plan takes item 2). No Java or Scala source uses `org.apache.log4j` directly; the code logs through SLF4J (`IngestDelegate`, `PublishDelegate`, `RunFailure`, `ProcessSparkJobLauncher`). `workflow-app/src/main/resources/logback.xml` is the logging config that should apply.
- Entry points: `com.example.salesbatch.workflow.WorkflowServer` (main class of the shaded `workflow-app/target/workflow-app.jar`); the Spark job in the shaded `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar` (`SalesAggregator`), run on EMR 5.36.
- Test command (as the pipeline runs it): `mvn -B clean test -Dsurefire.rerunFailingTestsCount=2 -Dsurefire.runOrder=alphabetical` at the root. Build: `mvn -B package -DskipTests`. UI: `cd ui && npm ci && npx ng test --watch=false --browsers=ChromeHeadless` (not affected).
- Pipeline: `.github/workflows/ci.yml` (jobs `java`, `ui`, `acceptance`, `ac4`). Last run on main 5a91d26: success, https://github.com/pratapsuraj/java-poc1.8/actions/runs/45975279021

### Baseline (main 5a91d26, local, Java 8 Corretto 1.8.0_512, Maven 3.9.16)

| Module | Run | Pass | Skipped | Fail |
|---|---|---|---|---|
| common | 30 | 30 | 0 | 0 |
| spark-job | 7 | 5 | 2 (`SalesAggregatorHBaseTest`, needs a live HBase) | 0 |
| workflow-app | 30 | 30 | 0 | 0 |
| Java total | 67 | 65 | 2 | 0 |
| UI (from SBM-2's card file, not re-run: untouched) | 25 | 25 | 0 | 0 |

Log: `<programme>/.prime/tmp/SBM-7/baseline-test.log`.

### Where each critical comes from (traced with `mvn dependency:tree`)

| Package | Path | Shipped inside the shaded jar? |
|---|---|---|
| log4j 1.2.17 | common -> hbase-client 1.4.13 -> hbase-annotations | workflow-app.jar: yes, including `net/SocketServer`, `jdbc/JDBCAppender`, `chainsaw/*` |
| log4j 1.2.17 | spark-job -> spark-core 2.4.8 (provided), and hbase-server (compile) | spark-job jar: yes, the same classes (the compile path through hbase-server puts them in the jar) |
| ZooKeeper 3.4.10 | common -> hbase-client 1.4.13 | workflow-app.jar: yes |
| ZooKeeper 3.4.6 | spark-job (Spark/HBase) | spark-job jar: yes |
| slf4j-log4j12 1.6.1 / 1.7.16 | ZooKeeper / Spark | both jars |

The ZooKeeper critical is the quorum-peer SASL authorization bypass: it concerns ZooKeeper servers with SASL quorum auth, not the client library this app ships (readiness brief: "only with SASL quorum auth, not default"). No ZooKeeper 3.4.x release fixes it; the fixed lines are 3.7.2, 3.8.3 and 3.9.1, far from what HBase 1.4.13 and EMR 5.36 use.

Finding beyond the vulnerability: in workflow-app both `slf4j-log4j12` and `logback-classic` are SLF4J bindings. The tests show "Actual binding is of type Log4jLoggerFactory", and the shaded jar holds the log4j binder (`org/slf4j/impl/StaticLoggerBinder.class` dated 2010, from slf4j-log4j12 1.6.1). With no log4j config, every application log line is dropped ("log4j:WARN No appenders could be found"); `logback.xml` is never used.

### Change readiness (judged by hand)

`prime:change-readiness`'s `change.py` could not be run in this session (its path is outside the folders this session may read), so the verdict is applied by hand from its rules: **proceed with review**. The existing tests notice a break of this change (trial 1 below: 2 errors in spark-job); the change is more than a minor bump, so not "proceed". No test looks at log output, so the logging difference below is seen only by the side-by-side.

### Side-by-side (trial change on a scratch copy, not on the branch)

Scratch worktree `<programme>/.prime/tmp/SBM-7/trial` from main.

- Trial 1: exclude `log4j` and `slf4j-log4j12` from `hbase-client` in `common`, add `log4j-over-slf4j` in `common`. Result: BUILD FAILURE, spark-job `SalesAggregatorTest` 2 errors, "Detected both log4j-over-slf4j.jar AND bound slf4j-log4j12.jar on the class path" (Spark brings its own binding). So the bridge must not reach spark-job. Log `trial-test.log`.
- Trial 2: the same exclusions in `common`, bridge in `workflow-app` only (7 lines). Result: 67 run, 65 pass, 2 skipped, 0 fail, the same as baseline. Log `trial2-test.log`. workflow-app.jar then has no log4j 1.2 classes (the bridge's `org/apache/log4j/Logger.class` only) and the logback binder; ZooKeeper 3.4.10 still inside. spark-job jar unchanged: still ships log4j 1.2.17 with SocketServer, JDBCAppender, Chainsaw, and ZooKeeper 3.4.6. Jar listings `jar-*.txt`.

Inputs: the 67 Java tests (the existing suite), the two shaded jars' contents, and the test runs' log output. Differences found, grouped:

1. workflow-app log output. Old: application and library log lines dropped, with "log4j:WARN No appenders" and an SLF4J multiple-bindings warning on stderr. New: logback writes INFO and above to stdout in `logback.xml`'s pattern (`HH:mm:ss.SSS LEVEL logger - msg`); Hadoop, HBase and ZooKeeper loggers at WARN. Direction: different (more output). Source: the change. Reach: user-reachable (operators' console/log files). Proposed category: intended.
2. workflow-app jar contents: log4j 1.2 classes replaced by the bridge. Direction: stricter (less code). Source: the change. Reach: API only. Proposed category: intended.
3. Test results: no difference (65/2/0 both).

Gap: the real artifact was not started side by side (running `java -jar` outside the session's folders needs a person's approval here). Build or Verify should start `WorkflowServer` from both jars and compare the console; the expected difference is group 1.

Scratch output (not committed): `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-7\` (`baseline-test.log`, `trial-test.log`, `trial2-test.log`, `deptree-*.txt`, `jar-*.txt`).

### Slice

One slice (`prime:slicing`, sizes in `.prime/facts.md`): build files only, about 20 lines, a roadmap of two or three steps, one readable pull request. A person can try it: build, start workflow-app and see its log lines appear, list the jars and see no `org/apache/log4j/net/SocketServer`. Keeps Java 8 and the EMR 5.36 pins (Spark 2.4.8, HBase 1.4.13, Hadoop 2.10.1).

### Decisions for Plan (one question, these defaults)

1. workflow-app logging: removing log4j makes `logback.xml` take effect, so log lines that are dropped today appear on the console. Default: intended (accept the difference). Other: keep today's output by setting logback's root to OFF (keeps hiding errors).
2. spark-job jar: it ships log4j 1.2.17 (and ZooKeeper 3.4.6) although Spark on EMR provides its own. Default: exclude log4j and slf4j-log4j12 from the shaded spark-job jar, so the job uses the cluster's logging; ZooKeeper stays in the jar (the HBase client needs it). Other: leave spark-job as it is (the jar keeps shipping the vulnerable classes).
3. ZooKeeper critical: it applies to ZooKeeper servers with SASL quorum auth, not to the client the app ships, and no 3.4.x fixes it. Default: record it as not reachable in the card file and close it with the risk, keeping ZooKeeper under the EMR pin. Other: bump the client to 3.5 or later (outside HBase 1.4.13's tested range; a pin change).

Standing answers that apply: SA-2 (existing tests stay unchanged and must pass), SA-3 (tool choice: `log4j-over-slf4j` over `reload4j` because it removes log4j 1.2 altogether and lets the logback config the project already has take effect; reload4j would keep log output dropped).

Outside the card: the EMR 5.36 cluster itself runs log4j 1.2.17 for Spark and HBase; this card cannot change that under the pins. Raised as a risk comment on the card.

Decisions taken by the card lead: worked on a separate worktree of the repository (`<programme>/.prime/tmp/SBM-7/wt`, branch `card/SBM-7`) so the person's working copy stays on `card/SBM-6`.
