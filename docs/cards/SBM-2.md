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
