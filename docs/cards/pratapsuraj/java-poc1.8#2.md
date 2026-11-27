# Card pratapsuraj/java-poc1.8#2: Add a pipeline that runs the tests

Card: https://github.com/pratapsuraj/java-poc1.8/issues/2
Branch: `card/java-poc1.8-2` (https://github.com/pratapsuraj/java-poc1.8/tree/card/java-poc1.8-2).
The branch name drops the `#` and `/` of the card key because `#` breaks branch links and, as found below, local tool URLs.

## Assess, the card lead, 2026-11-27

### Change map

| What | Where |
|---|---|
| New file the change lands in | `.github/workflows/ci.yml` (the repository has no `.github/` today) |
| Backend build and tests | root `pom.xml`, modules `common`, `spark-job` (Scala 2.11, scala-maven-plugin 4.8.1), `workflow-app`; JDK 8 required (README 2.1) |
| Backend test command | `mvn -B clean test` from the repository root (67 tests; surefire 2.22.2, JUnit 4.13.2; jacoco report bound to `test`) |
| UI folder and install | `ui/`, `npm ci` (lock file present, Node 20.19 or newer; tested here on 24.14.0) |
| UI test command | `cd ui && npx ng test --watch=false --browsers=ChromeHeadless` (25 tests, Karma with Angular's built-in Karma config, no `karma.conf.js`) |
| Tests needing services | `spark-job` `SalesAggregatorHBaseTest` (2 tests) use HBase on localhost and skip themselves when it is absent (JUnit `Assume`) |
| Not touched | all of `common`, `spark-job`, `workflow-app`, `ui` sources and tests, the poms, `docker/`, `sample-data/` |

### Baseline (before the change, commit 845b371)

Run on this machine (Windows 11, Corretto 1.8.0_512, Maven 3.9.16, Node 24.14.0, Chrome 158) in scratch clones under the programme's `.prime/tmp/`.

| Suite | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| `mvn -B clean test` | 67 | 64 | 1 | 2 (HBase not running) |
| UI `ng test` ChromeHeadless | 25 | 25 | 0 | 0 |

Per module: common 30/30; spark-job 7 run, 5 passed, 2 skipped; workflow-app 30 run, 29 passed, 1 failed.

**A flaky existing test class.** `workflow-app` `SalesBatchProcessTest` failed in 3 of 4 runs, on different tests:
- full run: `sparkFailureFailsTheRunWithTheReason:107` expected the process to have ended, it was still running;
- reruns of the class alone: `approvedBatchIsPublished:73` expected 1 approval task, found 0 (twice); one rerun passed.

Cause (read, not yet proven by a fix): the delegates write the run's status (`WAITING_APPROVAL`, `FAILED`) to the repository inside the Activiti job (`SparkJobDelegate.java:32`, `RunFailure.java:25`) before the job's transaction commits and the process reaches the approval task or its end. `Await.status` sees the new status and the test then queries Activiti too early. The same window exists for a real client: it can see `WAITING_APPROVAL` and approve before the task exists (`409 not waiting for approval`). Raised as a risk on the card.

Consequence for this card: a pipeline that runs the tests as they are will be red on most runs through no fault of the pipeline. Fixing it needs a change to `workflow-app` code, which this card's draft criterion 3 rules out, and the existing tests may not change (standing answer SA-2). This is item 2 of Plan's question below.

Logs (scratch, deletable): `C:\Work\javapoc1.8-programme\.prime\tmp\pratapsuraj\java-poc1.8#2\` `mvn-baseline.log`, `flaky-1.log` to `flaky-3.log`, `ui-baseline.log`.
The UI tests had to run from a second scratch clone, `.prime\tmp\pratapsuraj\java-poc1.8-2-ui`, because Karma cannot load files from a path containing `#`.

### Change readiness

Verdict: **proceed with review** (the card lead's judgement by the skill's rules; the skill's `change.py` did not run: the session's guard asks before running the plugin's Python scripts, and this machine has Python 3.11 where the readiness scripts want 3.12; Pim's brief notes the same).
- Blast radius: one new file under `.github/workflows/`; no source, test or build file changes. Nothing at runtime changes.
- What would notice a break: the pipeline itself, plus a deliberate failing-test check for "the pipeline fails when a test fails" (Test design).
- No "don't" signal: no check passes on the base and fails on the head; the one base failure is the flaky class above.

### Side-by-side run

Exempt: the change adds a pipeline definition and alters no code path, so there is no old and new application to compare. Behaviour kept is shown instead by the diff (only `.github/` and `docs/cards/` change) and by the same test counts in the pipeline as in the baseline above.

### Slice

One slice (`prime:slicing`): one new file of well under 100 lines, one pull request, a person can try it by opening a pull request and watching the checks. Within the size limits in `.prime/facts.md`.
The race fix is a separate piece of work (a code change in `workflow-app` with its own test); proposed as a new card in Plan's question, not a slice of this one.

### Decisions a person will need to make in Plan (with proposed defaults)

1. **Criteria.** Default, numbered:
   - AC1: A pull request to main, and a push to main, start the pipeline, which runs `mvn -B clean test` and the UI tests.
   - AC2: The pipeline fails when a Java test or a UI test fails.
   - AC3: No existing source, test or build file changes; the change adds only the pipeline file (and the card file).
   - AC4: On the card's pull request the pipeline passes, running every existing Java and UI test (the two HBase tests may skip).
   Push to main is included because Release's exit needs a pipeline run on main after the merge.
2. **The flaky test class.** Choices: (a) the pipeline reruns a failed Java test up to 2 times (`-Dsurefire.rerunFailingTestsCount=2` on the command line, no pom change) and the reruns show in the log, and a new card fixes the race; (b) a new card fixes the race first and this card waits for it; (c) no reruns, accept a red pipeline most runs. **Default (a)**: the pipeline is useful at once, a test that fails every time still fails it, and the race gets its own fix with a test.
3. **Where the pipeline runs.** Choices: (a) Linux runner (`ubuntu-latest`) with Temurin JDK 8 and Node 24; (b) Windows runner, the README's tested platform, slower and costlier. **Default (a)**; Test design checks the test counts match the baseline, and any Linux-only difference is written here.

Not asked (decided here, SA-3): Maven and npm caches in the pipeline (yes, `actions/setup-java` and `actions/setup-node` built-in caching); making the check required on main is a repository setting a person changes after merge, noted for Release.

### Exit criteria

- Branch exists and is linked from the card: `card/java-poc1.8-2`, linked in the done comment.
- Application folder and test commands on the card: change map above, summarised in the done comment.
- Tests passing before the change: Java 64 of 67 (1 flaky failure, 2 skipped), UI 25 of 25.
- One slice: yes, above.
