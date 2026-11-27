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
