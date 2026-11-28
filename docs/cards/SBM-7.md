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

## Plan, the card lead, 2026-11-27

Asked SBM-Q10 (criteria, three items with defaults: log output intended; strip log4j from the Spark job jar and record the ZooKeeper critical as not reachable; criteria AC1 to AC4). The card waits in Plan on that answer.

### Checkpoint (superseded below)

- Learned: see Assess. The working fix is trial 2 (exclusions in `common`, `log4j-over-slf4j` in `workflow-app` only); a bridge that reaches spark-job breaks its tests.
- Open: the answer to SBM-Q10. Then copy the criteria word for word onto the card's description, write the design and roadmap here, and have a fresh helper read it.
- Next step: a fresh lead reads SBM-Q10's answer and carries on with Plan step 2.

### Plan exit criteria

| Criterion | How it holds |
|---|---|
| Numbered criteria written by a person are on the card | AC1 to AC4 from the person's answer to SBM-Q10, copied word for word onto the card's description. |
| The design and roadmap are committed on the card's branch | Sections "Design" and "Roadmap" below, on `card/SBM-7`. |
| A helper with fresh context read the design, every finding resolved or answered | Gap: no fresh-context helper could be started here (see "Design review"); the card lead's own review, five findings, each resolved or answered, backed by trial 3. |

### Answer and criteria (SBM-Q10, answered by a person 2026-11-27 12:38, all defaults)

1. Log output: intended. Errors become visible; hiding them keeps today's silence.
2. Scope: yes to both. Strip log4j from the Spark job's jar; record the ZooKeeper critical as not reachable (a ZooKeeper bump would break the EMR pin).
3. Criteria, as written (copied word for word onto the card's description):
   - AC1 workflow-app jar has no log4j 1.2 classes.
   - AC2 Spark job jar has no log4j 1.2 classes.
   - AC3 workflow-app log lines reach the console.
   - AC4 existing tests unchanged and pass, new tests pass, Java 8 and EMR 5.36 versions unchanged.

### Design

Build files only; no Java or Scala source changes (none uses `org.apache.log4j`).

- **workflow-app (AC1, AC3).** In `common/pom.xml`, add exclusions `log4j:log4j` and `org.slf4j:slf4j-log4j12` to `hbase-client`. In `workflow-app/pom.xml`, add `org.slf4j:log4j-over-slf4j:${slf4j.version}` (compile). Result: HBase and ZooKeeper code that calls `org.apache.log4j.*` lands on the bridge, the only SLF4J binding left is `logback-classic`, and `workflow-app/src/main/resources/logback.xml` takes effect. The bridge goes in workflow-app only: in `common` it reaches spark-job, where Spark's own `slf4j-log4j12` makes SLF4J refuse to start (Assess trial 1).
- **spark-job (AC2).** In `spark-job/pom.xml`'s shade configuration, add `<artifactSet><excludes>` for `log4j:log4j` and `org.slf4j:slf4j-log4j12`. The shade filter keeps them out of the jar whatever path brings them in (`hbase-server` compile, `common`), while the test classpath keeps them through `spark-core` (provided), which is what the EMR cluster supplies at runtime. Chosen over dependency exclusions on `hbase-server` because it does not change the test classpath and does not depend on Maven's nearest-wins scope resolution.
- **ZooKeeper critical.** No change: ZooKeeper 3.4.x stays in both jars (the HBase client needs it). Recorded as not reachable (server-side SASL quorum auth; no 3.4.x fix; a bump breaks the EMR pin), per item 2. Build writes this into the card file's Build section with the advisory id from the osv report.
- **Versions (AC4).** No change to `java.version`, `spark.version`, `hbase.version`, `hadoop.version`, `scala.version` in the root `pom.xml`. `log4j-over-slf4j` takes the existing `slf4j.version` (1.7.30), so it matches `slf4j-api`.

What the acceptance tests should check (for Test design; the tests are theirs to write):

- AC1, AC2: after `mvn -B package -DskipTests`, list each shaded jar and fail on log4j 1.2's own entries: `META-INF/maven/log4j/log4j/`, `org/apache/log4j/net/`, `org/apache/log4j/jdbc/`, `org/apache/log4j/chainsaw/`, `org/apache/log4j/PropertyConfigurator.class`, and `slf4j-log4j12`'s `org/slf4j/impl/Log4jLoggerFactory.class` / `META-INF/maven/org.slf4j/slf4j-log4j12/`. Do not ban `org/apache/log4j/` as a whole: the bridge's `org/apache/log4j/Logger.class` and `org/apache/log4j/Log4jLoggerFactory.class` are expected in workflow-app.jar. A stronger form: every `org/apache/log4j/` entry in workflow-app.jar must also be in the `log4j-over-slf4j` jar from the local Maven repository.
- AC3: start `WorkflowServer` from `workflow-app/target/workflow-app.jar` (or log through SLF4J in a process using the shaded jar's classpath), capture stdout, and look for a known log line in `logback.xml`'s pattern; also fail on "log4j:WARN No appenders".
- AC4: the pipeline's `java` job (existing tests, unchanged) plus a check that the five version properties in the root `pom.xml` equal today's values.

### Roadmap

Each step leaves `main`'s tests passing (Assess trial 2 is steps 1 and 2 together).

1. `common/pom.xml` exclusions and `workflow-app/pom.xml` bridge, in one commit: separately, the exclusions alone would leave HBase's log4j calls with no classes (`NoClassDefFoundError` at runtime). Run the full test command; expect 65 pass, 2 skipped.
2. `spark-job/pom.xml` shade `artifactSet` excludes. Run the full test command and `mvn -B package -DskipTests`; list `spark-job` jar.
3. Evidence: acceptance tests green; side-by-side start of `WorkflowServer` from main's jar and the branch's jar (the gap Assess left), expecting only difference group 1; ZooKeeper not-reachable record; pipeline run linked.

### Trial 3 (the whole design on the scratch copy, not on the branch)

Scratch worktree `<programme>/.prime/tmp/SBM-7/trial` (trial 2 plus spark-job's shade `artifactSet` excludes). `mvn -B clean package -Dsurefire.runOrder=alphabetical`: BUILD SUCCESS; common 30/30, spark-job 7 run 5 pass 2 skipped, workflow-app 30/30, so 65 pass, 2 skipped, 0 fail as at baseline. Shade log: "Excluding log4j:log4j:jar:1.2.17" and "Excluding org.slf4j:slf4j-log4j12:jar:1.7.16 from the shaded jar". spark-job jar: no `org/apache/log4j/` entry at all, no `META-INF/maven/log4j/`. workflow-app.jar: no `net/`, `jdbc/`, `chainsaw/`, no `META-INF/maven/log4j/`; `org/apache/log4j/Log4jLoggerFactory.class` (the bridge's, 2019) and logback's `org/slf4j/impl/StaticLoggerBinder.class` (2017). Logs: `trial3-package.log`, `jar-trial3-spark.txt`, `jar-trial3-workflow.txt` in the scratch folder.

### Design review

Exit criterion "a helper with fresh context read the design" could not be met in this session: no subagent tool is available to the card lead here, and starting a separate `claude -p` helper needs a person's approval. Recorded as a gap outside the card. In its place the card lead reread the design against the poms and ran trial 3; findings and how each is resolved:

1. AC1/AC2 test wording could ban the bridge's own `org/apache/log4j/` classes and so fail a correct build. Resolved: the acceptance-check list now names log4j 1.2's own entries, and the definition is a recorded decision.
2. The design's first marker was `org/slf4j/impl/Log4jLoggerFactory`; the bridge ships `org/apache/log4j/Log4jLoggerFactory`, a different path. Resolved: both named, so the test cannot confuse them.
3. Untested shade `artifactSet` route for spark-job. Resolved by trial 3 (tests unchanged and green, jar clean).
4. Runtime on EMR: the spark-job jar's HBase classes that call log4j now rely on the cluster's log4j 1.2.17 on Spark's classpath. Answered: that is what the person chose in item 2 ("the cluster supplies its own"); the job cannot be run on EMR from here, so Verify notes it as not proven on a cluster.
5. The pipeline's SBM-2 no-build-file-changes check will fail on this card. Answered: see the pipeline dependency decision below.

The next lead (Test design or a reviewer) may still run a fresh-context read; nothing in Test design depends on it.

### Decisions taken by the card lead in Plan

- Shade `artifactSet` excludes for spark-job rather than dependency exclusions: see Design.
- No OpenRewrite or other recipe: the change is three small pom edits, which a recipe would not shorten.
- Defined "log4j 1.2 classes" (AC1, AC2) as classes that come from the `log4j:log4j` artifact. The bridge `log4j-over-slf4j` also puts classes under `org/apache/log4j/` (for example `Logger`, `Log4jLoggerFactory`); they are SLF4J's, not log4j 1.2's, and carry none of the vulnerable code. This reading follows the person's item 2 answer and Assess's trial 2, which the person saw. A test that bans all of `org/apache/log4j/` would contradict AC3's design.
- Pipeline dependency: `main`'s pipeline still runs SBM-2's check that no existing build file changes (`AC3_no_existing_source_test_or_build_file_changes.sh`), which this card's pom edits will fail. SBM-6's branch removes that check. Build rebases `card/SBM-7` on `main` once SBM-6 is merged; if it is not merged by then, Build records the failing check as caused outside the card rather than editing SBM-2's check here.

## Test design, the card lead, 2026-11-28

A first Test design lead drafted the tests and the pipeline job on 2026-11-27 and its claim lapsed before it committed; this lead checked the drafts, ran them, and committed them unchanged.

### Acceptance tests (one per criterion, in `.github/acceptance/SBM-7/`, run by the pipeline job `sbm7-acceptance` after `mvn -B package -DskipTests`)

| Criterion | Test | What it checks |
|---|---|---|
| AC1 | `AC1_workflow_app_jar_has_no_log4j_1_2_classes.sh` | Lists `workflow-app/target/workflow-app.jar`; fails on any `org/apache/log4j/` class outside the 30 names `log4j-over-slf4j` 1.7.30 ships, on `META-INF/maven/log4j/log4j/`, `META-INF/maven/org.slf4j/slf4j-log4j12/` and `org/slf4j/impl/Log4jLoggerFactory.class` (shared helper `log4j12_check.sh`; the definition is Plan's recorded decision). |
| AC2 | `AC2_spark_job_jar_has_no_log4j_1_2_classes.sh` | The same check on `spark-job/target/spark-job-1.0.0-SNAPSHOT.jar`. |
| AC3 | `AC3_workflow_app_log_lines_reach_the_console.sh` | Starts the shaded jar with `java -jar` (as an operator would), reads stdout for up to 90 s; passes when a line in `logback.xml`'s pattern (`HH:mm:ss.SSS LEVEL logger - msg`) reaches stdout and "log4j:WARN No appenders could be found" is not printed. |
| AC4 | `AC4_existing_tests_unchanged_and_versions_unchanged.sh` | No existing test file (`*/src/test/*`, `ui/src/*.spec.ts`) modified, deleted or renamed against the base; root `pom.xml` keeps `java.version` 1.8, `spark.version` 2.4.8, `hbase.version` 1.4.13, `hadoop.version` 2.10.1, `scala.version` 2.11.12, `scala.binary.version` 2.11. "Existing tests pass" is observed as the pipeline's `java` job; "new tests pass" is AC1 to AC3 green in the same run. |

### Local runs before the commit (Java 8 Corretto 1.8.0_512, Windows Git Bash)

- AC1, AC2 on today's jars (worktree `wt`, branch as at b9d3996): both FAIL, 297 log4j 1.2 entries each (`AsyncAppender`, `META-INF/maven/log4j/log4j/` and the rest).
- AC1, AC2 on Plan's trial 3 jars (`<programme>/.prime/tmp/SBM-7/trial`): both ok. So they tell the two apart.
- AC3 on today's jar: FAIL, no logback line on stdout and "log4j:WARN No appenders" on stderr (`td-ac3-before.txt`). On the trial 3 jar: ok, 56 log lines (`td-ac3-trial.txt`). The app stops on its own without HBase (RetriesExhaustedException) in both; Jetty and Spring log before that, which is all AC3 needs.
- AC4 run from the person's working copy on `card/SBM-6` (the session cannot run git scripts inside the scratch worktree): it reported SBM-6's five changed test files and no version failure, so both halves work. On this branch it is expected to pass.

### Which criteria are new behaviour and which are kept

- AC1, AC2, AC3: new behaviour. The pipeline on the test commit must show them failing (below).
- AC4: kept behaviour; it passes before and after. Sensitivity check Build must run (`prime-sensitivity`, scratch copy): change `<spark.version>2.4.8</spark.version>` to `2.4.7` in the root `pom.xml`, AC4 must FAIL naming `spark.version`; and edit one line of an existing test (for example a comment in `common/src/test/.../*Test.java`) and commit it in the copy, AC4 must FAIL naming that file.
- Sensitivity for AC1 to AC3 after the build: revert the `spark-job` shade `artifactSet` excludes, AC2 must fail; drop `log4j-over-slf4j` and the `common` exclusions, AC1 and AC3 must fail.

### Pipeline on the test commit

See "Pipeline result" below (filled after the push).
