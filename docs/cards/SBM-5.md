# Card SBM-5: Fix the approval race in the workflow app

Card: Relay SBM-5, a slice of risk SBM-3 (the approval race). Branch: `card/SBM-5` (https://github.com/pratapsuraj/java-poc1.8/tree/card/SBM-5), off `main` at 5a91d26 (SBM-2's pipeline merged).
Standing facts: `C:\Work\javapoc1.8-programme\.prime\facts.md`. Earlier baseline notes: `docs/cards/SBM-2.md`.

## Assess, the card lead, 2026-11-27

### The race, confirmed from the code

Steps `ingest` and `spark` run in the background job executor (`activiti:async="true"`). Inside those jobs the delegates write the run's audit status straight to the repository, before the engine's transaction commits:

- `SparkJobDelegate.java:32` writes `WAITING_APPROVAL` while the engine has not yet created (committed) the `approval` user task. A client that reads `WAITING_APPROVAL` and approves at once finds no task: `BatchService.approve` throws `IllegalStateException`, which `BatchController` maps to `409 Conflict`.
- `RunFailure.java:25` writes `FAILED` while the run is still alive; a client sees `FAILED` before the process instance has ended.
- Not affected: `AuditStartListener` (`QUEUED`, inside the start request, which returns after commit), `IngestDelegate` `RUNNING` (an in-progress status, no action depends on it), `PublishDelegate` `COMPLETED` and `RejectDelegate` `REJECTED` (synchronous inside `approve`, which returns after commit).

Both symptoms showed in the baseline runs below: `approvedBatchIsPublished` found 0 approval tasks at `WAITING_APPROVAL` (`SalesBatchProcessTest.java:73`), and `sparkFailureFailsTheRunWithTheReason` found the process instance still alive at `FAILED` (line 107).

### Change map

| What | Where |
|---|---|
| Application folder | `workflow-app/` (module of the root `pom.xml`; depends on `common`) |
| Files the change lands in | `workflow-app/src/main/java/com/example/salesbatch/workflow/delegate/SparkJobDelegate.java`, `RunFailure.java` (status written after commit); possibly one small new helper in the same package |
| Entry points | REST `POST /batches` start, `GET /batches/{id}` status, `POST /batches/{id}/approve` (`web/BatchController.java`, 409 handler line 105); `service/BatchService.java` `start`, `status`, `approve`; process `src/main/resources/processes/sales-batch.bpmn20.xml` |
| Status store | `common` `SalesRepository.updateAudit` (HBase in production, `InMemorySalesRepository` in tests); not changed |
| Existing tests that see the race | `workflow-app` `SalesBatchProcessTest` (7 tests); unchanged (standing answer SA-2) |
| New tests | Acceptance tests in `workflow-app/src/test/java/...` named after the person's criteria (Test design) |
| Test command, whole build | `mvn -B clean test -Dsurefire.runOrder=alphabetical` from the repository root, JDK 8 (the pipeline adds `-Dsurefire.rerunFailingTestsCount=2`) |
| Test command, this module | `mvn -B test -pl workflow-app -am -Dtest=SalesBatchProcessTest -DfailIfNoTests=false` |
| UI tests | `cd ui && npm ci && npx ng test --watch=false --browsers=ChromeHeadless` (25); the change does not touch `ui/` |
| Not touched | `ui/`, `spark-job/`, `common/`, poms, `docker/`, `sample-data/`, `.github/` (the pipeline's retries stay; removing them is a later card per the card's description) |

### Baseline (before the change, `main` 5a91d26)

Windows 11, Corretto 1.8.0_512, Maven 3.9.16; fresh clone in `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-5\base`, no retries.

| Suite | Run | Passed | Failed | Skipped |
|---|---|---|---|---|
| `mvn -B clean test -Dsurefire.runOrder=alphabetical` | 67 | 63 | 2 (`SalesBatchProcessTest`: `approvedBatchIsPublished`, `sparkFailureFailsTheRunWithTheReason`) | 2 (HBase not running) |
| Pipeline on `main`, run https://github.com/pratapsuraj/java-poc1.8/actions/runs/45975279021 (retries up to 2) | 67 Java, 25 UI | 65 Java, 25 UI | 0 | 2 |

Log: `.prime/tmp/SBM-5/mvn-baseline.log` (per module 30/0/0, 7/0/2, 30/2/0).
Flakiness of `SalesBatchProcessTest` alone, repeated runs with no retries: red in 6 of 6 runs, one test each time (`approvedBatchIsPublished` 3, `startReturnsBeforeTheBackgroundStepsFinish` 2, `sparkFailureFailsTheRunWithTheReason` 1), every one the race: no approval task yet at `WAITING_APPROVAL`, or the run still alive at `FAILED` (`.prime/tmp/SBM-5/flaky-summary.txt`, logs `flaky-1.log` onward).

### Side-by-side run (plan; nothing new to run against yet)

The change promises to keep every status, result and report and to change only when `WAITING_APPROVAL` and `FAILED` become visible. Harness for Verify, old (`main`) against new (the card's head), in-process through `BatchService` and through the REST API with the test infrastructure:
- Inputs: the CSVs the existing tests use, `sample-data/` CSVs, a header-only file, a bad number, a Spark failure, approve and reject; each run 50 times.
- Record per run: the status sequence, the final status, the error text, the report file's content, and the HTTP code of an approve sent the moment `WAITING_APPROVAL` is read.
- Environment axis: exempt. No runtime, library or locale-sensitive code changes; the change is when a status is written.

First draft of the known-differences ledger (Plan has a person categorise it):
1. Approve sent at once after `WAITING_APPROVAL`: old sometimes `409`, new always `200`. Direction stricter, source the change, user-reachable. Proposed category: intended.
2. At `FAILED` the run has ended: old sometimes still alive, new always ended. Direction stricter, source the change, API only. Proposed category: intended.

### Change readiness

Proceed with review: two delegates in one module change when they write a status; no API, schema or test change. What could break: a status written after commit is lost if the repository write fails after the commit (today the write fails the step instead). Plan settles how that case behaves.

### Slice

One slice (`prime:slicing`): about 40 to 80 lines of source in one module plus new tests, one pull request, well inside a day. A person tries it by starting a run (UI or `POST /batches`) and approving the moment it shows "waiting for approval": no `409`, and repeated test runs with no retries pass. Removing the pipeline's retries is a later card, as the card's description says.

### For Plan

Criteria are a person's to write. Draft to propose, with defaults:
- AC1 once a run shows `WAITING_APPROVAL`, approving it at once succeeds (never `409`).
- AC2 once a run shows `FAILED`, it has ended and has no approval task.
- AC3 no existing test changes, and `SalesBatchProcessTest` passes 20 runs in a row with no retries.
- AC4 every other status, result and report is as before (side-by-side against `main`).

Standing answers read: `.prime/answers.yaml` (SA-2: existing tests stay unchanged; SA-3: tool choices are the agent's); Relay has none.

### Exit criteria

- Branch exists and is linked from the card: `card/SBM-5`, on origin, linked on Relay.
- Application folder and test command on the card: change map above, summarised in the done comment.
- Tests passing before the change: Java 63 of 67 locally with no retries (2 race failures, 2 HBase skips); the pipeline on `main` 65 of 67 with retries; UI 25 of 25.
- One slice: yes.

## Plan, the card lead, 2026-11-27

Criteria are a person's act, so Plan asked once on Relay (kind criteria) before designing, with three items and defaults:
1. Criteria, default as drafted in Assess ("For Plan"), with AC3 worded so no count depends on new tests: "the existing tests are unchanged and pass in 20 runs in a row with no retries, and the new tests pass".
2. Categories for the two known differences (approve no longer gets `409`; at `FAILED` the run has ended): default intended.
3. If writing a status fails after the engine has committed: default retry the write up to 3 times, then log an error and leave the last status (the run itself is unaffected); the alternative, writing before commit as today, keeps the race.

The card waits on the person; Relay released the claim. Next step: on the answer, copy the criteria onto the card word for word; design (status written in a transaction listener on commit, `Context.getCommandContext().getTransactionContext().addTransactionListener(TransactionState.COMMITTED, ...)` in Activiti 5.22) and roadmap here; a fresh helper reads them.

### The answer (SBM-Q6, a person, 2026-11-27 10:53, all defaults)

Criteria, copied word for word onto the card's description:

> AC1 approving at once after 'waiting for approval' never gets 409. AC2 a run shown failed has ended, with no approval task. AC3 existing tests unchanged, pass 20 runs in a row without retries; new tests pass. AC4 all else as before, side-by-side with main.

Known differences: both intended (an immediate approve no longer gets `409`; at `FAILED` the run has ended). A status write that fails after commit: retry up to 3 times, then log an error and keep the last status.

### Design

Cause: the two statuses a client acts on (`WAITING_APPROVAL`, `FAILED`) are written to the status store inside the background job's transaction, before the engine commits the state they describe (the approval task; the ended run). Fix: write them only after that transaction has committed.

1. New package-private helper `delegate/AfterCommit.java`, one static method `status(SalesRepository repo, String pid, String status)`. Inside a running engine command it registers `Context.getCommandContext().getTransactionContext().addTransactionListener(TransactionState.COMMITTED, listener)`. With `SpringProcessEngineConfiguration` the transaction context is `SpringTransactionContext`, which runs the listener from Spring's `afterCommit`; nothing fires if the job rolls back (the job executor then retries the job, and the next attempt registers again).
2. The listener captures `pid` and `status` as plain values (never the `DelegateExecution`, which is stale after commit). It:
   - writes only if the stored status is still `RUNNING` (read `auditStatus`, then `updateAudit`). This keeps a late write from overwriting `COMPLETED`/`REJECTED` if a task-based approve lands in the instant between commit and the write. The read and write are not atomic; the remaining window is microseconds and needs an approve that did not wait for the status, so it is recorded as a residual, not closed (closing it needs a conditional write in `common`, which this card does not touch);
   - on an exception, tries again up to 3 more times with a short pause (100 ms, 200 ms, 400 ms), then logs an error naming the run and the status and gives up, leaving the last status (the person's item 3);
   - catches every `RuntimeException` itself (errors such as `OutOfMemoryError` still propagate): an exception escaping `afterCommit` would reach the job executor after its job is already committed.
3. `SparkJobDelegate`: line 32 becomes `AfterCommit.status(repository, pid, "WAITING_APPROVAL")`.
4. `RunFailure.mark`: keeps `LOG.error`, `recordError` and `setVariable("failed", true)` where they are; only `updateAudit(pid, "FAILED", null)` becomes `AfterCommit.status(...)`. The error text may show while the status is still `RUNNING`, as today (no client acts on it).
5. Unchanged: `AuditStartListener` (`QUEUED`), `IngestDelegate` (`RUNNING`), `PublishDelegate`, `RejectDelegate` (synchronous inside the approve request, which returns after commit), the BPMN, `BatchService`, `BatchController`, `common`, the poms, every existing test (standing answer SA-2).

Why a transaction listener, not other ways (agent's choice, standing answer SA-3):
- Spring's `TransactionSynchronizationManager.registerSynchronization` directly: same effect, public Spring API, but throws when no Spring transaction is active; the Activiti transaction context works with Spring and with a plain engine.
- An extra async step or an execution listener on the user task's `create` event: still inside the same uncommitted transaction, so the race stays.
- Deriving the status from the engine in `BatchService.status` (task present means waiting): changes what the status store holds, which the UI and the HBase audit table read; larger change.
- Cost: `TransactionListener`/`TransactionState` live in Activiti's `impl.cfg` package (internal API). They are the same in Activiti 6 and Flowable 5/6, so an engine upgrade keeps them, but they are worth a look in any engine upgrade. Recorded here; small enough not to raise as a risk.

Residuals (accepted by the answer to item 3, recorded for Verify): a status write that fails four times leaves the run at `RUNNING` while the engine has moved on; a process crash between commit and the write does the same. Both are visible in the error log.

### Roadmap

Each step leaves `main`'s behaviour as the person asked; tests come first (Test design), then the build.

1. Test design: new class `workflow-app/src/test/java/com/example/salesbatch/workflow/ApprovalRaceAcceptanceTest.java`, tests named after the criteria, committed before any source change, draft pull request opened:
   - `ac1_approveAtOnceAfterWaitingApprovalSucceeds`: through `BatchController` (MockMvc, as `BatchControllerTest`), start a run, poll status with no sleep, and the moment it reads `WAITING_APPROVAL` approve; expect `200` and `COMPLETED`. Repeated 30 runs in the one test (the race lost in 6 of 6 class runs at baseline, so 30 runs make a false pass unlikely).
   - `ac1_approvalTaskExistsWhenWaitingApprovalIsWritten`: deterministic probe. A test-only subclass of `InMemorySalesRepository` in its own Spring context on its own H2 database (review finding 1), on `updateAudit(pid, "WAITING_APPROVAL")`, asks from another thread (bounded wait) whether the approval task is visible. Before the fix the write happens inside the uncommitted transaction, so the task is not visible (or the query waits on the lock); after the fix it always is.
   - `ac2_failedRunHasEndedWithNoApprovalTask`: Spark failure and bad CSV, poll to `FAILED`, then at once assert no process instance and no approval task; plus the same probe at the `FAILED` write. Repeated 30 runs.
   - `ac3_...`: not a JUnit test. Proof is the run of the existing `SalesBatchProcessTest` 20 times in a row with no retries (`-Dsurefire.rerunFailingTestsCount=0`), and `git diff main -- '*/src/test/**'` showing only added files. Card file records both.
   - `ac4_...`: the side-by-side run planned in Assess (old `main` against the card's head, 50 runs per input), its ledger compared to the two intended differences; done in Verify, harness in the scratch folder. Within the suite: `ac4_statusSequenceIsUnchanged` asserts the recorded status sequence per run is exactly `QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED` (approve), `... REJECTED` (reject), `QUEUED, RUNNING, FAILED` (failure), as on `main`.
   - Sensitivity: the probe tests fail on today's code every run; the pipeline run on the test commit shows them failing.
2. Build, commit 1: `AfterCommit` helper with its own unit test (listener registered, write after commit only, `RUNNING` guard, retry 3 then log, no exception escapes).
3. Build, commit 2: `SparkJobDelegate` and `RunFailure` call it. Acceptance tests go green, unchanged.
4. Build, proof: whole build `mvn -B clean test -Dsurefire.runOrder=alphabetical`; `SalesBatchProcessTest` 20 runs in a row with no retries; pipeline on the head green; pull request ready for review. Pipeline retries stay (a later card removes them).

### Fresh-context review of the design

Gap: this session has no tool to start a helper agent (no subagent tool; starting `claude -p` needs an approval the run cannot get; the `nw:review` skill needs the same missing tool). So no helper with fresh context has read the design yet. Recorded here and in the Plan done comment; the blind reviewer at Review reads the built change with fresh context, and a later lead with a subagent tool should have a helper read this section before Build.

In its place, the card lead re-read the design against the code (not fresh context; says so). Findings and how each is settled:

1. Probe tests in their own Spring context would share the in-memory H2 database `jdbc:h2:mem:activiti` with the cached context of the other test classes, and both job executors would take each other's jobs. Settled: the probe test class sets its own database with `@TestPropertySource(properties = "activiti.jdbc.url=jdbc:h2:mem:sbm5probe;DB_CLOSE_DELAY=-1")` (`EngineConfig` reads that property through `AppConfig`'s placeholder configurer) and its own test-only configuration whose repository is a probe subclass of `InMemorySalesRepository`. No existing test file changes.
2. Would the probe fail on today's code? H2 is 1.4.197 (MVStore, read committed), so another thread does not see the uncommitted approval task, or sees the process instance still alive: count 0 / instance present, every run. Settled: yes, deterministic.
3. The listener must not call the engine from the committing thread (the closing command context is still on Activiti's stack). Settled: the listener only calls the repository; the probe asks the engine from another thread.
4. `afterCommit` exceptions reach the job executor after its job is committed. Settled in the design (catch inside the listener).
5. Other pre-commit writes: `RUNNING` and `recordError` stay inside the job transaction; if the job rolls back and is retried they are written again with the same values. Harmless, as today; no client acts on them.
6. The `RUNNING` guard on every path that uses it: ingest failure, Spark failure and Spark success all have `RUNNING` stored just before (written by `IngestDelegate` in the earlier or same job). Settled: no path changes.

## Test design, the card lead, 2026-11-27

Tests: `workflow-app/src/test/java/com/example/salesbatch/workflow/ApprovalRaceAcceptanceTest.java`, own Spring context on its own H2 database (`jdbc:h2:mem:sbm5probe`), a probe status store (subclass of `InMemorySalesRepository`) that, the moment `WAITING_APPROVAL` or `FAILED` is written, asks the engine from another thread what a client would see. Committed at 82caf5f, then 5b9f50c (the probe's threads were shut down after the class, so the pipeline's reruns of a failed test hung at `RUNNING`; now daemon threads that stay up). No source change in either commit; no existing file changed (`git diff --name-status origin/main...5b9f50c`: two added files, this one and the test).

| Criterion | Test | On today's code |
|---|---|---|
| AC1 | `ac1_approveAtOnceAfterWaitingApprovalNeverGets409` (30 runs over REST, approve the moment the status shows) | fails: `409 No pending approval` (local and pipeline, every attempt) |
| AC1 | `ac1_approvalTaskExistsWhenWaitingApprovalIsShown` (probe, 5 runs) | fails: 0 approval tasks visible when the status was written (deterministic) |
| AC2 | `ac2_sparkFailureShownFailedHasEndedWithNoApprovalTask`, `ac2_badCsvShownFailedHasEndedWithNoApprovalTask` (15 runs each, plus probe) | fail: run still alive when shown `FAILED` |
| AC3 | `ac3_existingWorkflowTestsPass20RunsInARowWithoutRetries` (runs `SalesBatchProcessTest` and `BatchControllerTest` 20 times in-process, no reruns); "unchanged" is the diff above | fails locally (run 2 and run 6 of 20 in two runs); passed in both pipeline runs. A weak signal on the pipeline's runner; the AC1/AC2 tests carry the proof there |
| AC4 | `ac4_statusSequencesAreAsBefore` (status sequence for approve, reject, bad CSV, Spark failure; report lines; error text) | passes, as it should (behaviour kept); the full side-by-side against `main` is in Verify |

Pipeline on the test commit 5b9f50c: https://github.com/pratapsuraj/java-poc1.8/actions/runs/45987853559 , java job red: `ApprovalRaceAcceptanceTest` 4 failing tests (AC1 x2, AC2 x2) in all 3 attempts, every other Java class passes; UI 25 of 25. Earlier run on 82caf5f: https://github.com/pratapsuraj/java-poc1.8/actions/runs/45986994560 . Logs: `.prime/tmp/SBM-5/pipeline-red.log`, `pipeline-red2.log`, local `td-red.log`.

Draft pull request: https://github.com/pratapsuraj/java-poc1.8/pull/7 , linked on the card.

Found: SBM-2's pipeline check "AC3 no existing source, test or build file changes" (`.github/acceptance/AC3_...sh`, step in `.github/workflows/ci.yml`) fails on this pull request because a new test file lies outside `.github/` and `docs/`. It was SBM-2's own criterion, merged into `main`'s pipeline, so it fails every later pull request that changes code. Under standing answer SA-2 an agent may not change an existing test, so Build cannot make the pipeline green alone; see Build.

## Build, the card lead, 2026-11-27

Commits: 30f97a1 (`delegate/AfterCommit.java`, the after-commit write with the `RUNNING` guard, 3 retries at 100/200/400 ms then an error log, never throws; unit test `delegate/AfterCommitTest.java`, 4 tests), bbcd5be (`SparkJobDelegate` and `RunFailure` call it; nothing else in them changes). Design as in Plan, no deviation. Acceptance tests unchanged since 5b9f50c (`git diff 5b9f50c bbcd5be -- '*src/test*'` adds only `AfterCommitTest.java`).

| Check | Result |
|---|---|
| Local whole build `mvn -B clean test -Dsurefire.runOrder=alphabetical`, no retries | BUILD SUCCESS, 77 run (67 before + 6 acceptance + 4 unit), 0 failed, 2 skipped (HBase) (`.prime/tmp/SBM-5/build-full.log`) |
| AC1, AC2 acceptance tests | pass (red on 5b9f50c, green on bbcd5be: the fix is what turns them) |
| AC3 test (existing `SalesBatchProcessTest` and `BatchControllerTest`, 20 runs in a row, no reruns) | pass locally and on the pipeline; existing test files unchanged (`git diff --name-status origin/main...bbcd5be` shows no modified file under `src/test`) |
| AC4 test (status sequences, report, error text) | pass; full side-by-side is Verify's |
| Pipeline on bbcd5be https://github.com/pratapsuraj/java-poc1.8/actions/runs/45988861463 | java green (30 + 7 + 40, 2 skipped, no test needed a retry), ui green 25/25, SBM-2's AC4 job green; **SBM-2's AC3 step red**: it lists the 5 code files this card adds or changes |

Not met: "the pipeline on the latest commit passes". The only red step is SBM-2's check that a pull request changes no code, which every code change fails. Changing or removing it changes an existing test (SA-2 says no) and is a person's decision, so the card is parked with one question, default: remove that one step from `.github/workflows/ci.yml` in this card, keeping SBM-2's script in `.github/acceptance/` as its record. The pull request stays a draft until the pipeline is green.

Checkpoint (for the lead who carries on): on the answer, make the change it names in `.github/workflows/ci.yml` (one commit, nothing else), wait for the pipeline on that commit, record it here, mark pull request #7 ready for review (`gh pr ready 7`), and move the card to Ready for review with the Build done comment. If the person chooses to leave it red, record that and do the same.

### Build, continued after SBM-Q7, the card lead, 2026-11-27

The answer (SBM-Q7, a person, 2026-11-27 11:39, default): remove SBM-2's "no code changes" step from the pipeline in this card, keeping its script as SBM-2's record. Done in 78d8ed8: one change, `.github/workflows/ci.yml` loses the step "AC3 no existing source, test or build file changes" (a comment says why); `.github/acceptance/AC3_...sh` stays. Nothing else in the commit.

| Exit criterion | How it holds |
|---|---|
| The pipeline on the latest commit passes, the acceptance tests unchanged | Run on 78d8ed8 https://github.com/pratapsuraj/java-poc1.8/actions/runs/45992539751 : all four jobs green; java 30 + 7 + 40 run, 0 failed, 2 skipped (HBase), no `Flakes` line (no test needed a retry); ui 25/25; SBM-2's AC1, AC2 and AC4 green. Log: `.prime/tmp/SBM-5/pipeline-final-java.log`. `git diff --stat 5b9f50c 78d8ed8 -- '*src/test*'` adds only `AfterCommitTest.java`: the acceptance tests are as committed in Test design |
| The pull request is ready for review | https://github.com/pratapsuraj/java-poc1.8/pull/7 marked ready (`gh pr ready 7`) |

Note: the pipeline still passes `-Dsurefire.rerunFailingTestsCount=2` (SBM-2's decision); removing the retries is the later card the description names. AC3's "without retries" is shown by the AC3 acceptance test, which runs the two existing classes 20 times in-process with no reruns.

### Build, after Review sent it back, the card lead, 2026-11-28

The blind reviewer's findings and how each is settled:

1. `AfterCommit` guarded with a read then a write (`auditStatus`, then `updateAudit`), so an approve that did not wait could write `COMPLETED`/`REJECTED` between the two and be overwritten. **Fixed**: the write is now one conditional step. `common` `SalesRepository` gains `updateAuditIfStatus(pid, expected, status, approver)` (returns whether it wrote); `HBaseSalesRepository` implements it with one HBase `checkAndPut` on `audit:status` (atomic per row, HBase 1.4 API), sharing the `Put` it builds for `updateAudit`. The interface's default (read then write) is for single-writer stores only and says so; the only other implementation is the test double `InMemorySalesRepository`, left unchanged (SA-2). `AfterCommit` calls the conditional write; the class comment now says how the guard holds. This widens the change map by `common` (two files), a deviation from Plan's "common not touched", taken because the reviewer's fix needs an atomic write in the store (SA-3, tool choice). Residual from Plan's design note closed.
2. Sleeping retries hold the job executor thread up to 700 ms (nit). **Answered, not changed**: the pauses happen only when the store fails, after the job has committed, bounded at 700 ms per write; the person chose retry 3 times (SBM-Q6 item 3). A separate executor would add a thread pool for a failure path. Noted in the class comment.

Tests added: `common` `HBaseConditionalAuditTest` (3: one `checkAndPut` on the expected status with no `get`/`put`; returns false when the status moved on; an `IOException` is reported) and `AfterCommitTest.writesOnlyThroughTheStoresConditionalWrite`. Sensitivity: on the previous source both fail (`HBaseConditionalAuditTest` 3 errors; `AfterCommitTest` `expected:<0> but was:<1>`, the late write lands) — logs `.prime/tmp/SBM-5/sensitivity-review-fix.log`, `sensitivity-review-fix2.log`. Acceptance tests unchanged (`ApprovalRaceAcceptanceTest.java` untouched since 5b9f50c).

Local whole build `mvn -B clean test -Dsurefire.runOrder=alphabetical`, no retries: BUILD SUCCESS, 81 run (33 + 7 + 41), 0 failed, 2 skipped (HBase) (`.prime/tmp/SBM-5/build-review-fix.log`).
