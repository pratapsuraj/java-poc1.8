# Card SBM-2: Add a pipeline that runs the tests

Card: Relay SBM-2 (continues GitHub issue https://github.com/pratapsuraj/java-poc1.8/issues/2).
Branch: `card/java-poc1.8-2` (https://github.com/pratapsuraj/java-poc1.8/tree/card/java-poc1.8-2), kept from the GitHub card so the earlier work and its links stay valid.
Earlier card file, with the full Assess detail: [docs/cards/pratapsuraj/java-poc1.8#2.md](pratapsuraj/java-poc1.8%232.md). This file is now the card's file; the earlier one is kept, not moved, so its links keep working.

## Assess, the card lead, 2026-11-27

The card came to Relay after Assess was done on the GitHub issue, on the same base commit (845b371, still `main`'s head). Nothing in the code has changed since, so that assessment holds and is carried over here rather than redone.

### Change map

| What | Where |
|---|---|
| New file the change lands in | `.github/workflows/ci.yml` (no `.github/` today) |
| Application folders | root `pom.xml` with modules `common`, `spark-job` (Scala 2.11), `workflow-app`; UI in `ui/` (Angular) |
| Backend test command | `mvn -B clean test` from the repository root, JDK 8 (67 tests) |
| UI test command | `cd ui && npm ci && npx ng test --watch=false --browsers=ChromeHeadless` (25 tests, Node 20.19 or newer) |
| Tests needing services | `spark-job` `SalesAggregatorHBaseTest` (2 tests) skip themselves without HBase on localhost |
| Not touched | all sources, tests, poms, `docker/`, `sample-data/` |

### Baseline (before the change, commit 845b371)

Windows 11, Corretto 1.8.0_512, Maven 3.9.16, Node 24.14.0, Chrome 158; scratch clones.

| Suite | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| `mvn -B clean test` | 67 | 64 | 1 | 2 (HBase not running) |
| UI `ng test` ChromeHeadless | 25 | 25 | 0 | 0 |

Logs (copied for this card): `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-2\mvn-baseline.log` (lines 68, 330, 402: per-module totals 30/0/0, 7/0/2, 30/1/0), `ui-baseline.log` (line 58: `TOTAL: 25 SUCCESS`), `flaky-1.log` to `flaky-3.log`.

The one failure is the flaky class `workflow-app` `SalesBatchProcessTest`: it failed in 3 of 4 runs, on different tests. Cause (read from the code, not yet proven by a fix): the delegates write the run's status before the workflow engine's transaction commits and reaches the approval task or end (`SparkJobDelegate.java:32`, `RunFailure.java:25`). A real client sees the same window (approve too early, `409`). Raised as a risk on the card. For this card it means a pipeline running the tests as they are is red most runs; Plan's question item 2 covers it.

### Side-by-side run

Exempt: the change adds a pipeline definition and alters no code path. Kept behaviour is shown by the diff (only `.github/` and `docs/cards/`) and by the pipeline's test counts matching the baseline.

### Change readiness

Proceed with review: one new file, nothing at runtime changes; the pipeline itself and a deliberate failing-test check (Test design) notice a break.

### Slice

One slice (`prime:slicing`): one new file under 100 lines, one pull request; a person tries it by opening a pull request and watching the checks. The race fix is separate work (a `workflow-app` code change with its own test), proposed as its own card, not a slice of this one.

### Decisions for Plan

The question with three items and defaults (criteria AC1 to AC4; retry the flaky class up to 2 times with a new card for the race; Linux runner with JDK 8) was asked on the GitHub issue: https://github.com/pratapsuraj/java-poc1.8/issues/2#issuecomment-7213437379. It has no answer there.
The person's move of this card into Next on Relay carries the note "Person: pilot card, defaults accepted". Plan reads that as the answer it may be and decides then; Plan needs criteria written by a person on this card, so if the note is not enough it asks once on Relay with the same defaults. Standing answers read: `.prime/answers.yaml` (SA-2 applies: existing tests stay unchanged); Relay has none.

### Exit criteria

- Branch exists and is linked from the card: `card/java-poc1.8-2`, on origin, linked on Relay.
- Application folder and test commands on the card: change map above, summarised in the done comment.
- Tests passing before the change: Java 64 of 67 (1 flaky failure, 2 skipped), UI 25 of 25.
- One slice: yes.

## Plan, the card lead, 2026-11-27

The move note "defaults accepted" is not criteria written on the card, and criteria are a person's act, so Plan asked once on Relay (SBM-Q1, kind criteria) with the same three items and defaults. The card waits on the person; Relay released the claim.
Next step: on the answer, copy the criteria onto the card word for word, write the design and roadmap here, have a fresh helper read them.

### Answer (SBM-Q1, answered by the person 2026-11-27 08:53, all defaults accepted)

Criteria, word for word, also copied onto the card's description:

- AC1 a pull request or push to main starts the pipeline: mvn -B clean test and the UI tests.
- AC2 it fails when a Java or UI test fails.
- AC3 no existing source, test or build file changes.
- AC4 on this card's pull request it passes, running every existing test (2 HBase tests may skip).

Decisions: (2a) retry a failed Java test up to 2 times; a new card fixes the race (risk SBM-3). (3a) Linux with Java 8.

### Design

One new file, `.github/workflows/ci.yml`, two jobs that run side by side on `ubuntu-24.04`:

| Job | Steps |
|---|---|
| `java` | `actions/checkout@v4`; `actions/setup-java@v4` (Temurin 8, `cache: maven`); `mvn -B clean test -Dsurefire.rerunFailingTestsCount=2` |
| `ui` | `actions/checkout@v4`; `actions/setup-node@v4` (Node 22, `cache: npm`, `cache-dependency-path: ui/package-lock.json`); `npm ci` and `npx ng test --watch=false --browsers=ChromeHeadless` in `ui/` (Chrome is on the runner image) |

- Triggers: `pull_request` (any base) and `push` to `main` (AC1). `workflow_dispatch` too, for re-runs by hand.
- `permissions: contents: read`; `concurrency` per ref with `cancel-in-progress` so pushes to one pull request do not queue.
- Retry (decision 2a) comes from the command line, not the pom (AC3): surefire 2.22.2 with JUnit 4.13.2 supports `rerunFailingTestsCount`; a test that passes on a rerun is reported as a flake and the build passes, one that fails three times fails the build (AC2 holds). The UI tests get no retry: they passed 25 of 25 and none is known flaky.
- Test reports (`**/target/surefire-reports/*.xml`, UI console log) are uploaded as artifacts with `if: always()` so counts can be read on a failing run too (proof for AC4).
- Why these choices (SA-3, the agent decides): Temurin is the setup-java default with JDK 8 on Linux x64; Node 22 meets Angular 20's floor (20.19) and is an LTS; actions pinned to major tags, as is common, not to SHAs (noted as a choice a person may tighten).

Not in this card: the race fix (risk SBM-3, own card), any pom, source or test change, HBase in the pipeline (the 2 HBase tests keep skipping, as AC4 allows).

### How each criterion is proved (for Test design)

| Criterion | Proof |
|---|---|
| AC1 | The draft pull request starts both jobs (run link); a check script `AC1_` reads `ci.yml` and asserts the triggers and the two commands. |
| AC2 | Sensitivity: on a scratch branch off the card's branch (never merged), one deliberately failing Java test, then one failing UI spec; each run is red (run links), then the branch is deleted. A check script `AC2_` asserts no `continue-on-error` and no `|| true` in `ci.yml`. |
| AC3 | `git diff --name-only origin/main...HEAD` lists only `.github/` and `docs/` (and any new acceptance-check files); a check script `AC3_` asserts it. |
| AC4 | The pull request's run is green; surefire reports show 67 run, at most 2 skipped (the HBase ones), every existing test class present; the UI log shows `TOTAL: 25 SUCCESS`. |

Acceptance checks are new files under `.github/acceptance/` (names start `AC1_` ... `AC4_`), run by a third small job `acceptance` in the same workflow so the pipeline shows them failing before `ci.yml` exists in full. Test design settles the exact form.

### Roadmap

1. Test design: add `.github/acceptance/AC1_`..`AC4_` checks and a minimal workflow that runs only them; commit, push, open the draft pull request; the run shows them failing (no `java`/`ui` jobs yet).
2. Build: add the `java` job; push; check counts against the baseline (64+ pass, flaky class passes or shows as flake, 2 skipped).
3. Build: add the `ui` job; push; 25 of 25.
4. Build: AC2 sensitivity run on a scratch branch, links in this file, branch deleted.
5. Build: pipeline green on the latest commit with the acceptance checks unchanged; pull request ready for review.

Each step changes only `.github/` and this file, so no commit changes the application's behaviour.

### Design read with fresh context

Gap: this session has no tool to start a helper agent, and running a separate `claude -p` reviewer needed a person's approval, which a non-interactive run cannot get. So the design was checked by the card lead against a written list instead. The Review stage's fresh reviewer reads it again. Findings and what was done:

1. The AC3 check diffs against `origin/main`: the `acceptance` job must check out with `fetch-depth: 0`. Added to the design (resolved).
2. AC4's "every existing test class" needs a fixed list: the AC4 check takes the test classes under `*/src/test` on `main` (845b371) and requires a surefire report for each, so the list does not depend on counts the new tests change (resolved).
3. A test rerun by surefire still counts once in the report (`flakyFailure` entries inside the same `testcase`), so 67 stays 67 (answered).
4. Spark 2.4 local mode on Linux JDK 8 needs no Hadoop `winutils`; runner hostnames resolve. A failure here shows on the first `java` run in Build, step 2 (answered, watched).
5. Maven 3.9 and Chrome are on the `ubuntu-24.04` image; no install step (answered).
6. AC2 with retries: a test that always fails fails 3 times and the job is red, so retries do not hide a real failure (answered; the sensitivity run proves it).

## Test design, the card lead, 2026-11-27

Acceptance checks, one per criterion, in `.github/acceptance/` (commit 2cb5b86, before any build), run by the pipeline itself (`.github/workflows/ci.yml`, at this commit only the `acceptance` and `ac4` jobs):

| Criterion | Check | Kind | On 2cb5b86 |
|---|---|---|---|
| AC1 | `AC1_pull_request_or_push_to_main_starts_java_and_ui_tests.sh`: triggers, `mvn -B clean test`, `npm ci`, `ng test --watch=false --browsers=ChromeHeadless` in `ui/`. Observed half: the pull request's run shows the `java` and `ui` jobs | new | fails (4 of 7 checks: no test commands yet) |
| AC2 | `AC2_fails_when_a_java_or_ui_test_fails.sh`: no `continue-on-error`, no hidden exit code, no skip or ignore flags, Java retries set and at most 2. Observed half: the sensitivity run below | new | fails (no retry count yet) |
| AC3 | `AC3_no_existing_source_test_or_build_file_changes.sh`: every file changed against the base is new and under `.github/` or `docs/` | kept | passes, as it should: nothing outside them has changed |
| AC4 | `AC4_passes_running_every_existing_test.sh`: every class with a JUnit `@Test` under `src/test` on `main` (16) has a surefire report; no failure or error; at most 2 skipped; the UI log shows `TOTAL: n SUCCESS` and no failure | new | fails (no reports yet) |

Pipeline on 2cb5b86: https://github.com/pratapsuraj/java-poc1.8/actions/runs/45969935868 (red: AC1, AC2, AC4 fail; AC3 passes).
Draft pull request: https://github.com/pratapsuraj/java-poc1.8/pull/5, linked on the card.

Sensitivity checks Build must run:
- AC3 (passes before the build): on a scratch copy, add a line to `pom.xml` and commit; AC3 must fail. Local run is enough, it is a script.
- AC2 (observed): a scratch branch off the card's branch with `@Test public void ac2Sensitivity() { org.junit.Assert.fail(); }` added to a new class in `common/src/test`; the `java` job must be red after its 2 retries. Then one with a failing `it(...)` in a new `ui/src/app/ac2-sensitivity.spec.ts`; the `ui` job must be red. Run links here; the scratch branches are deleted, never merged.
- AC4: on the green run, the check's output counts (16 classes, Java totals, UI total) go here.

Decision (SA-3): `actions/checkout@v4` warns that Node 20 is deprecated on the runners, so Build uses the v5 majors of `checkout`, `setup-java` and `setup-node`.

## Build, the card lead, 2026-11-27

`.github/workflows/ci.yml` now has the `java` and `ui` jobs beside the acceptance jobs. The acceptance checks in `.github/acceptance/` are unchanged since 2cb5b86 (`git diff 2cb5b86 HEAD -- .github/acceptance` is empty).

### What happened on the way

- First build run (65906a8, https://github.com/pratapsuraj/java-poc1.8/actions/runs/45970170956): red. `SalesBatchProcessTest.approvedBatchIsPublished` failed all 3 tries with `NoSuchFileException: /tmp/sales-batch-test-reports/report-20261113.csv`. Not the known race: on Linux, surefire's file order ran `WebConfigTest` first; it starts `workflow-app`'s shared Spring context without setting `report.dir`, so the context wrote reports to `/tmp/sales-batch-reports` and later classes looked in the wrong folder. On Windows (the baseline) the order was alphabetical and `BatchControllerTest` set it first.
- Decision (SA-3, an option of the tool; tests unchanged per SA-2 and AC3): `-Dsurefire.runOrder=alphabetical`, the baseline's order. The order dependence itself stays in the tests; raised as a risk on the card.
- `upload-artifact` and `download-artifact` moved to v7 and v8, the majors that run on Node 24, after the runners warned v5 targets the deprecated Node 20.

### Evidence per criterion

| Criterion | Result |
|---|---|
| AC1 | Pull request run starts `java`, `ui`, `acceptance`, `ac4`; AC1 check 7 of 7 ok. |
| AC2 | AC2 check 4 of 4 ok. Sensitivity: scratch pull request https://github.com/pratapsuraj/java-poc1.8/pull/6 (base: the card's branch, closed unmerged, branch deleted), run https://github.com/pratapsuraj/java-poc1.8/actions/runs/45970587387: `java` red (`Ac2SensitivityTest` failed runs 1, 2 and 3), `ui` red (`Executed 26 of 26 (1 FAILED)`). |
| AC3 | AC3 check ok on the pull request. Sensitivity: in a scratch clone, one line added to `pom.xml` and committed; the check printed `FAIL AC3 existing file changed (M): pom.xml` and exited 1. The scratch pull request's run also failed AC3 on its two new test files. |
| AC4 | Run https://github.com/pratapsuraj/java-poc1.8/actions/runs/45970557374 (aee05c3), green: 16 existing test classes all reported; Java 67 run, 0 failed, 0 errors, 2 skipped (the HBase ones); per module 30/0/0, 7/0/2, 30/0/0, the baseline's counts with the flaky class passing; UI `TOTAL: 25 SUCCESS`. |

Logs beside the scratch folder: `build-run1-failed.log`, `build-run2.log`, `sens-ac2-run.log` in `.prime/tmp/SBM-2/`.
The pipeline on the latest commit is read after the push below; its link is in the done comment.

## Verify, Verify, 2026-11-27

Source-blind: the change's workflow was read only for its triggers and test commands; evidence comes from the pipeline's runs read with `gh`, the diff's file list, and mutants run against the acceptance checks. Logs and the mutant script are in `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-2\` (`verify-*.log`, `verify-mutants.sh`, `verify-mutants/`).

### Evidence per criterion

Pull request https://github.com/pratapsuraj/java-poc1.8/pull/5, head a7de2d6 (the latest code commit; this card-file commit adds only this section and its own run is linked in the done comment).

| Criterion | Test | Run | Result |
|---|---|---|---|
| AC1 | `AC1_pull_request_or_push_to_main_starts_java_and_ui_tests.sh` (7 checks) and the pull request starting `java` and `ui` | green https://github.com/pratapsuraj/java-poc1.8/actions/runs/45970999609; red before the build https://github.com/pratapsuraj/java-poc1.8/actions/runs/45969935868 | pass, 7 of 7; `java`, `ui`, `acceptance`, `ac4` all ran on `pull_request`. Push to main can only be observed after merge; the trigger is checked statically. |
| AC2 | `AC2_fails_when_a_java_or_ui_test_fails.sh` (4 checks) and the observed sensitivity run | green 45970999609; red https://github.com/pratapsuraj/java-poc1.8/actions/runs/45970587387 | pass, 4 of 4; in the red run `Ac2SensitivityTest` failed runs 1, 2, 3 and `java` ended `BUILD FAILURE`; `ui` `TOTAL: 1 FAILED, 25 SUCCESS`, exit 1. |
| AC3 | `AC3_no_existing_source_test_or_build_file_changes.sh` | green 45970999609; red 45970587387 (new test files outside `.github/`, `docs/`) | pass; and `git diff --name-status origin/main...a7de2d6` lists 7 files, all `A`, under `.github/` and `docs/`; no file modified or deleted. |
| AC4 | `AC4_passes_running_every_existing_test.sh` | green 45970999609 (`ac4` job); red before the build 45969935868 | pass: 16 existing test classes reported; Java per module 30/0/0, 7/0/2 (HBase skipped), 30/0/0 = 67 run, 0 failed, 2 skipped, `BUILD SUCCESS`; UI `TOTAL: 25 SUCCESS`. |

### Side-by-side

No existing file differs between `main` (845b371) and the head, so the application built from either is the same source; a run of old against new would compare identical code. Environment axis: the pipeline on Linux, Java 8 gives the Windows baseline's per-module counts (30/7/30, 2 skipped); the one difference, the flaky class passing, is the known race (risk SBM-3) and goes the expected way. Known-differences ledger: none recorded and none found.

### Sensitivity

Mutants of `ci.yml` at a7de2d6 run against the AC1 and AC2 checks (`verify-mutants.sh`): 10 of 10 killed (no pull request trigger, no push trigger, push on another branch, `mvn compile` instead of `test`, UI in watch mode, 5 retries, `|| true`, `-DskipTests`, `maven.test.failure.ignore`, `continue-on-error`). AC3's own sensitivity: the red run 45970587387 failed AC3 on two new test files. The observed red run is on a scratch commit before aee05c3 and a7de2d6; those two commits change only test order and artifact action versions, not how a failure ends the job.

### Gaps

- Second verifier (Sonnet) not started: this session has no tool to start a helper agent. One verifier's verdict only.
- AC1's push-to-main half is observable only after the merge.
