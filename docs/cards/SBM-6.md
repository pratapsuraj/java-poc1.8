# SBM-6 Make the tests independent of run order

Slice of risk SBM-4 (option 1, chosen by a person in SBM-Q3). Branch `card/SBM-6`, from `main` at 5a91d26.

## Assess, the card lead, 2026-11-27

### Where the application sits

| What | Value |
|---|---|
| Application folder | `workflow-app/` (the order dependence); also `common/` and `spark-job/`, in the same Maven build |
| Test command (the pipeline's) | `mvn -B clean test -Dsurefire.rerunFailingTestsCount=2 -Dsurefire.runOrder=alphabetical` from the repository root, JDK 8 (`.github/workflows/ci.yml`, job `java`) |
| One module, chosen classes | `mvn -B test -pl workflow-app -am -DfailIfNoTests=false -Dtest=WebConfigTest,SalesBatchProcessTest -Dsurefire.runOrder=reversealphabetical` |
| UI | `ui/`, `npx ng test --watch=false --browsers=ChromeHeadless`; untouched by this card |
| Local JDK | Corretto 1.8.0_512 through Maven 3.9.16 (Windows 11) |

### Baseline (before any change)

`mvn -B clean test` at 5a91d26, alphabetical order, with and without the pipeline's retries: **67 run, 65 pass, 0 fail, 2 skipped** (the two HBase tests; no HBase running). Per module: `common` 30/0/0, `spark-job` 7/0/2, `workflow-app` 30/0/0. BUILD SUCCESS both times. Same counts as SBM-2's baseline.

### Change map

- Shared state, all in `workflow-app/src/test/java/com/example/salesbatch/workflow/`:
  - `report.dir` is a JVM system property read once, when the cached Spring context (`AppConfig` + `TestInfraConfig`) is first built (`PublishDelegate`, default `/tmp/sales-batch-reports` from `src/main/resources/application.properties`). Set in `@BeforeClass` by `BatchControllerTest`, `BatchControllerUploadTest`, `SalesBatchProcessTest`, and in `@Before` by `WorkflowServerTest`; never cleared. `WebConfigTest` and `DataControllerTest` use the same context and set nothing.
  - The report folder `<java.io.tmpdir>/sales-batch-test-reports` is shared by every class and every run, and never cleaned.
  - `TestInfraConfig.failSpark` and `sparkDelayMillis` are static flags; reset by some classes' `@Before`/`@After`, not all.
  - One cached Activiti engine (in-memory H2) and one `InMemorySalesRepository` across the Spring test classes; `WorkflowServerTest` uses its own database.
- Entry points: the test classes above, `TestInfraConfig` (shared fake infrastructure), `ci.yml` (the order flag).
- No production code needs to change for this card.

### What the change would really do: runs in other orders

Scripts and logs: `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-6\` (`orders.sh`, `repro.sh`, `stale.sh`, `summary.txt`, `repro-summary.txt`, `stale-summary.txt`).

Inputs: 6 full runs (alphabetical twice, reverse alphabetical, random with seeds 1, 2, 3) and 3 targeted runs. Differences: 2 groups.

1. **The order dependence (SBM-4), reproduced.** `WebConfigTest` before `SalesBatchProcessTest`, in a fresh temp folder: `approvedBatchIsPublished` fails with `NoSuchFileException: ...\sales-batch-test-reports\report-20261113.csv` (`repro-webconfig-first.log`). The context was built without `report.dir`, so the report went to `/tmp/sales-batch-reports`.
2. **A report left by an earlier run hides it.** All 6 full local runs passed, including random seed 1, which ran `WebConfigTest` before `SalesBatchProcessTest`. With a report seeded into the temp folder first, the failing order passes (`stale.sh`: exit 0). So on a developer's machine the dependence is masked by files from earlier runs; on a clean pipeline runner it is not (SBM-2's red run 45970170956). A fix needs each class to start from an empty report folder of its own, or the test passes for the wrong reason.

Not an order difference: random seed 1 had one flake, `BatchControllerTest.startThenApproveOverRest` (409 instead of 200), and the fresh-folder control run without retries failed `approvedBatchIsPublished` at line 73 (task count 0). Both are the known approval race (risk SBM-3, card SBM-5), not this card. `common` and `spark-job` passed in every order.

### Change readiness

`prime:change-readiness` could not run here: its `change.py` does not parse on this machine's `python3` (f-string with a backslash, needs Python 3.12). Judged by hand on its rules: the change touches test code only; the covering checks are the tests themselves plus the pipeline gate from SBM-2; a reverse-order run in a fresh folder is a check that tells the difference (fails before, should pass after). Verdict: **proceed with review**.

### Slice

One slice a person can try: "run the Java tests in any order on a clean machine and they pass". Small (about six test classes and one shared test config in one module, plus one pipeline flag). Not split.

### Decisions for Plan, with proposed defaults

1. **Changing existing test classes.** Standing answer SA-2 says existing tests stay unchanged; this card asks each test to set up and clean up its own state, keeping intent. Default: change only set-up and clean-up code (and the shared `TestInfraConfig`), never a test method's steps or assertions; the diff shows it. Alternative: touch no existing class, only shared support code, which may not be enough for the report folder.
2. **The pipeline's fixed order.** Default: drop `-Dsurefire.runOrder=alphabetical` from `ci.yml` and run in random order, so the pipeline keeps proving independence. Alternative: keep the alphabetical flag and prove independence only in an acceptance test.
3. **The approval race (SBM-5).** Default: keep the pipeline's two retries; this card does not depend on SBM-5 merging first.

### Exit criteria

- Branch exists and is linked: `card/SBM-6` pushed and linked on the card.
- Application folder and its test command: above.
- Tests passing before the change: 65 of 67, 2 skipped (above).
- One slice: yes (above).

## Plan, the card lead, 2026-11-27

Asked SBM-Q8 (criteria, set-up and clean-up changes under SA-2, pipeline order), defaults as in Assess's decisions. The card waits on a person; design and roadmap follow the answer.

### Checkpoint

- Learned: the dependence is `report.dir` fixed by whichever Spring test class builds the cached context first, plus a shared, never-cleaned report folder that hides it locally.
- Decisions: test-only change; one slice; verdict proceed with review (by hand).
- Open: SBM-Q8.
- Next: copy the answered criteria onto the card's description; design (likely: `TestInfraConfig` gives the context its own fresh report folder, classes read it from the context instead of a system property; remove leftovers in set-up), roadmap, fresh-context review, then Test design.
