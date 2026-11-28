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

Asked SBM-Q8 (criteria, set-up and clean-up changes under SA-2, pipeline order), defaults as in Assess's decisions. A person answered 2026-11-27 11:39, all defaults. The criteria are copied word for word onto the card's description:

- AC1 Java tests pass in reverse alphabetical order, temp folder free of earlier reports
- AC2 they pass in random order (three seeds) from such a folder
- AC3 the pipeline runs them in no fixed order and passes
- AC4 existing test steps and assertions unchanged, existing and new tests pass

Also settled by that answer: existing test classes may change set-up and clean-up code only (this narrows standing answer SA-2 for this card); `ci.yml` drops the fixed alphabetical order for random order.

### Design

The cause is one value read once: `PublishDelegate` gets `${report.dir}` when the cached Spring test context is built, so the class that happens to build it first decides where reports go. The fix makes the test context own its report folder instead of a JVM-wide system property.

1. `TestInfraConfig` (shared test support) gets a bean `testReportDir` (`java.nio.file.Path`): a new folder from `Files.createTempDirectory("sales-batch-test-reports")` per context, so it is empty when the context starts whatever ran before. It also declares `@Bean publishDelegate(SalesRepository, Path)` returning `new PublishDelegate(repository, dir.toString())`. A `@Bean` method silently replaces a component-scanned bean of the same name in Spring 4.3 (`ConfigurationClassBeanDefinitionReader`, scanned definitions are overridable), so the process's `${publishDelegate}` resolves to the test one. No production code changes.
2. Spring test classes (`BatchControllerTest`, `BatchControllerUploadTest`, `SalesBatchProcessTest`): set-up only. Drop the `@BeforeClass` that sets `report.dir`. `SalesBatchProcessTest` keeps its field name `reportDir` but fills it from the context (`@Autowired Path reportDir`, non-static), and its `@Before` empties that folder, so each test starts from an empty folder even within the shared context. Test methods are byte-identical.
3. `WorkflowServerTest` builds its own context with `TestInfraConfig` per test, so it gets a fresh folder too; drop its `report.dir` line from `@Before`. Its `@After` already clears its other property.
4. `ci.yml`: replace `-Dsurefire.runOrder=alphabetical` with `-Dsurefire.runOrder=random`, keep `-Dsurefire.rerunFailingTestsCount=2` (the approval race, card SBM-5).
5. Clean-up of the temp folders: the bean's destroy method deletes the folder (best effort), so runs leave nothing for the next run to hide behind.

Not chosen: `@DirtiesContext` (rebuilds the Activiti engine per class, slow, and still relies on the property); `@TestPropertySource` (static values, cannot give a fresh folder); an `ApplicationContextInitializer` (needs an annotation on every class, more change for the same effect).

`failSpark` and `sparkDelayMillis`: only `SalesBatchProcessTest` sets them to non-defaults and its `@After` resets both; the controller tests reset `failSpark` in `@Before`. No order effect seen in Assess; left as they are.

### Finding from planning: the Assess "random seeds" were not seeds

The root `pom.xml` pins `maven-surefire-plugin` 2.22.2. `-Dsurefire.runOrder.random.seed` exists only from 3.0.0-M6, so Assess's three "seeded" random runs were three unseeded random runs. AC2 needs three reproducible orders, so the acceptance test does its own seeded shuffle (below) rather than bump the build plugin, which would be a lasting build change outside this card.

### Roadmap

Test design (tests first, committed before any build, all failing or exempt for a stated reason):

1. `AC1_...` in `workflow-app/src/test/java/.../workflow/RunOrderAcceptanceTest.java` (name settled in Test design): forks a child JVM (`java -cp <test classpath> org.junit.runner.JUnitCore <classes>`) with `-Djava.io.tmpdir=<new empty folder>`, running every `workflow-app` test class except the acceptance class itself in reverse alphabetical order; asserts exit 0. Fails today (WebConfigTest before SalesBatchProcessTest).
2. `AC2_...`: the same fork three times, order `Collections.shuffle(classes, new Random(seed))` for seeds 1, 2, 3, each in a fresh tmpdir; asserts each exits 0 and names the seed and order on failure. Test design checks that at least one seed puts `WebConfigTest` before `SalesBatchProcessTest`, else picks seeds that do, so the test can fail today.
3. `AC3_...`: reads `.github/workflows/ci.yml` and asserts the Java test step has no fixed `runOrder` (`alphabetical`, `reversealphabetical`, `filesystem`), and has `runOrder=random`; the pipeline run on the Build commit is the rest of AC3's proof.
4. `AC4_...`: a fingerprint of every existing `@Test` method body in the three modules (SHA-256 of the normalised source), recorded from `main` at 5a91d26 into a test resource; the test recomputes and compares. Plus the full suite passing.
5. Commit, push, open the draft pull request, link it; pipeline on that commit shows AC1 to AC3 failing.

Build:

6. `TestInfraConfig`: `testReportDir` and `publishDelegate` beans (design 1, 5).
7. Set-up changes in the four test classes (design 2, 3). Run `mvn -B test -pl workflow-app -am -DfailIfNoTests=false -Dtest=WebConfigTest,SalesBatchProcessTest -Dsurefire.runOrder=reversealphabetical -Djava.io.tmpdir=<empty>`: passes.
8. `ci.yml` order flag (design 4).
9. Full `mvn -B clean test` locally in a fresh tmpdir, then the pipeline; sensitivity: revert step 6 locally and see AC1 fail.

### Decisions I took

- Replace the bean in test config rather than set the property earlier (SA-3: the agent picks the means). A static initialiser that sets `report.dir` first would still be JVM-wide and still share one folder across classes.
- Seeded shuffle in the acceptance test instead of upgrading Surefire (above).
- Forking a child JVM in AC1 and AC2 because Spring's context cache is per JVM: running classes in-process after the suite has built the context would prove nothing.

### Design review

Gap: this session could not start a helper with fresh context (no subagent tool here, and a separate headless `claude -p` was refused by the permission system). The exit criterion "a helper with fresh context read the design" is therefore not met by an independent reader; the next lead (Test design) should have one read this section first if it can, and answer its findings here. Until then I checked the design's risky claims against the code and the libraries myself; findings and how each is resolved:

1. **Classpath of the child JVM (resolved in the roadmap).** Surefire 2.22.2 forks with a manifest-only jar by default, so inside a test `java.class.path` is just `surefirebooter*.jar`. AC1 and AC2 must take the classpath from the system property `surefire.test.class.path` (set by Surefire in the fork), falling back to `java.class.path` in an IDE. Windows caps a command line at 32,767 characters and Java 8 has no `@argfile`, so pass the classpath through a small manifest-only jar written into the fresh temp folder (the same trick Surefire uses), not through `-cp`.
2. **Which classes the child runs.** Only classes in `workflow-app` test output with at least one `@Test` method, minus the acceptance class itself; `LocalDebugServer`, `InMemorySalesRepository`, `TestInfraConfig`, `LocalInfraConfig` and the Spark launcher helpers have none and are skipped by that rule.
3. **The bean override takes effect.** `@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})`: `AppConfig`'s `@ComponentScan` registers `publishDelegate` while parsing; `TestInfraConfig`'s `@Bean` definitions are loaded after and, in Spring 4.3, replace a scanned definition of the same name (`ConfigurationClassBeanDefinitionReader.isOverriddenByExistingBeanDefinition` returns false for a `ScannedGenericBeanDefinition`). Build step 7's targeted run is the check; if it fails, fall back to marking the test bean `@Primary` under a different name and resolving `${publishDelegate}` through an alias.
4. **Only `SalesBatchProcessTest` reads the report folder** (`grep reportDir`: lines 79 and 94 only). The other classes set `report.dir` but never read a report, so dropping their `@BeforeClass` changes no step or assertion.
5. **AC1 and AC2 can fail before the fix.** Without the fix the child JVM, given a fresh `java.io.tmpdir`, writes the report to `report.dir`'s default `/tmp/sales-batch-reports` whenever `WebConfigTest` or `DataControllerTest` builds the context first, and `approvedBatchIsPublished` then fails with `NoSuchFileException`, as Assess reproduced. Reverse alphabetical puts `WebConfigTest` before `SalesBatchProcessTest`.
6. **Run time.** AC1 and AC2 run the `workflow-app` suite four more times in the pipeline (about 4 x 30 s by Assess's logs). Accepted; noted for Build to measure.
7. **AC4 fingerprint.** Normalise each `@Test` method body (strip whitespace and comments) before hashing, so a line-ending change on Windows does not fail it; the recorded list comes from `git show 5a91d26:<file>`, written once in Test design.

## Test design, the card lead, 2026-11-27

Acceptance tests in `workflow-app/src/test/java/com/example/salesbatch/workflow/RunOrderAcceptanceTest.java`, commit d13a17e, before any build. Draft pull request: https://github.com/pratapsuraj/java-poc1.8/pull/8 (linked on the card).

| Criterion | Test | Before the fix (local, `mvn -B -pl workflow-app test -Dtest=RunOrderAcceptanceTest`) |
|---|---|---|
| AC1 | `AC1_javaTestsPassInReverseAlphabeticalOrderFromAFreshTempFolder` | passes (kept behaviour, see below) |
| AC2 | `AC2_javaTestsPassInRandomOrderForThreeSeedsFromFreshTempFolders` | **fails**: seed 7, `SalesBatchProcessTest.approvedBatchIsPublished`, `NoSuchFileException ...\sales-batch-test-reports\report-20261113.csv` (the SBM-4 failure) |
| AC3 | `AC3_pipelineRunsTheJavaTestsInNoFixedOrder` | **fails**: `ci.yml` still has `-Dsurefire.runOrder=alphabetical` |
| AC4 | `AC4_existingTestStepsAndAssertionsAreUnchanged` | passes (kept behaviour) |

Log: `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-6\td-before-fix.log`. AC3's other half is observed, not tested: the pipeline on the Build commit passes with the random order.

How they work:
- AC1 and AC2 start a child JVM (`RunOrderAcceptanceTest.Child`) on the test classpath (Surefire's `surefire.test.class.path`, passed through a manifest-only jar for Windows) with `java.io.tmpdir` set to a new empty folder under `workflow-app/target/sbm6-run-order/`, and run every `workflow-app` class with a `@Test` method, except the acceptance class, in the chosen order. A failed test is retried up to 2 times in the same JVM, as the pipeline does for the approval race (SBM-5); an order failure fails every try because the cached context does not change. Each child's log stays in its folder.
- AC2's seeds are 7, 12 and 19 (`Collections.shuffle(sorted classes, new Random(seed))`). Chosen because each puts `DataControllerTest` (7, 12) or `WebConfigTest` (19) first among the Spring classes, the orders that fail today; seeds 1 to 3 all happen to pass today, so they would not tell the difference. Orders computed with `seeds.py` in the scratch folder and checked against the child's own log for seed 2.
- AC1 passes before the fix: in reverse alphabetical order `WorkflowServerTest` runs before any other Spring class and sets `report.dir`, so the cached context is built right. AC1 is kept behaviour, and its sensitivity comes through AC2.
- AC4: `src/test/resources/acceptance/SBM-6-existing-test-methods.txt` holds a SHA-256 for each of the 67 existing `@Test` methods (Java and Scala, all three modules: 30 + 7 + 30, the same 67 as the baseline), comments and whitespace removed, recorded from the working tree when it matched 5a91d26 (`git diff 5a91d26 -- '*/src/test/java'` empty). It fails if any recorded method is missing or its text differs.

Sensitivity checks Build must run (prime:evidence):
1. With the fix in place, delete the `publishDelegate` bean from `TestInfraConfig` and restore the `@BeforeClass` blocks: AC2 must fail again (seed 7).
2. Change one assertion in an existing test method (for example `SalesBatchProcessTest`, `assertEquals` expected value): AC4 must fail naming that method. Revert.
3. Put `-Dsurefire.runOrder=alphabetical` back in `ci.yml`: AC3 must fail. Revert.

### Gap: card SBM-2's acceptance job

The pipeline's `acceptance` job still runs SBM-2's `AC3_no_existing_source_test_or_build_file_changes.sh` on every pull request: it fails any branch that changes an existing file or adds one outside `.github/` and `docs/`. This card's branch adds a test class and, in Build, changes test classes and `ci.yml`, so that job is red whatever this card's own tests show, and Build's exit criterion "the pipeline on the latest commit passes" cannot be met while it runs unchanged. Changing or removing another card's acceptance check is a person's decision; asked at the end of this stage. Risk note posted on the card.

### The pipeline on the test commit

Run 45995634424 on d13a17e (https://github.com/pratapsuraj/java-poc1.8/actions/runs/45995634424): `java` fails with exactly the new tests: AC2 (seed 7, the same `NoSuchFileException` on `report-20261113.csv`) and AC3, each tried three times; AC1 and AC4 pass; the 67 existing tests pass (65, 2 HBase skipped). `acceptance` fails on SBM-2's AC3 only, for the two new files (the gap above). `ac4` fails because `java` failed. `ui` passes.

### Exit criteria

- Every criterion has an acceptance test whose name starts with its number: yes (table above).
- Tests committed before any build, draft pull request linked: d13a17e, PR #8 linked on the card.
- The pipeline on that commit shows the new tests failing: AC2 and AC3 fail; AC1 and AC4 are kept behaviour and pass, with sensitivity checks listed for Build.

### Checkpoint

- Next: Build per the roadmap (steps 6 to 9), after the person answers the question on SBM-2's acceptance check; then the sensitivity checks above.
- Fresh-context review of the design (Plan's gap) still open: have one read "Design" and "Design review" before building, if a helper can be started.

## Build, the card lead, 2026-11-27

Answers acted on: SBM-Q9 (a person, 12:38, default): SBM-2's no-existing-file-changes check removed in its own commit.

### Commits

- 19e585f: `ci.yml` drops the `AC3 no existing source, test or build file changes` step and the script `.github/acceptance/AC3_no_existing_source_test_or_build_file_changes.sh` is deleted. SBM-2's AC1, AC2 and AC4 checks stay as they were.
- de9f5f2: the fix, as designed in Plan.
  - `TestInfraConfig`: beans `testReportDir` (`Files.createTempDirectory("sales-batch-test-reports")`, so under `java.io.tmpdir` and new per context), `testReportDirCleanup` (deletes it when the context closes) and `publishDelegate` (replaces the scanned production bean; Spring 4.3.30 overrides a scanned definition with a `@Bean` one). Helper `emptyFolder`.
  - `SalesBatchProcessTest`: the static `reportDir` and its `@BeforeClass` replaced by `@Autowired Path reportDir` plus a `@Before` that empties it. `BatchControllerTest`, `BatchControllerUploadTest`: `@BeforeClass` setting `report.dir` removed. `WorkflowServerTest`: the `report.dir` line in `@Before` removed. Unused imports removed. No test method changed (AC4 passes; `git diff d13a17e HEAD` on the four classes touches only fields, set-up methods and imports).
  - `ci.yml`: `-Dsurefire.runOrder=alphabetical` becomes `-Dsurefire.runOrder=random`, retries kept at 2.
- No production code changed. Acceptance tests and their resource unchanged since d13a17e (`git diff --stat d13a17e HEAD -- RunOrderAcceptanceTest.java src/test/resources` empty).

### Evidence

| Criterion | Test | Local | Pipeline run 46002534491 on de9f5f2 |
|---|---|---|---|
| AC1 | `AC1_javaTestsPassInReverseAlphabeticalOrderFromAFreshTempFolder` | pass | pass |
| AC2 | `AC2_javaTestsPassInRandomOrderForThreeSeedsFromFreshTempFolders` | pass (failed before the fix, seed 7) | pass |
| AC3 | `AC3_pipelineRunsTheJavaTestsInNoFixedOrder` | pass (failed before) | pass; the run itself used `runOrder=random` and passed |
| AC4 | `AC4_existingTestStepsAndAssertionsAreUnchanged` | pass | pass; the 67 existing tests pass (65, 2 HBase skipped) |

Pipeline: https://github.com/pratapsuraj/java-poc1.8/actions/runs/46002534491, all four jobs green (`java`, `ui`, `acceptance`, `ac4`). Java totals: `common` 30/0/0, `spark-job` 7/0/2 skipped, `workflow-app` 34/0/0 (30 existing + 4 acceptance), no flakes. Log: `.prime/tmp/SBM-6/pipeline-46002534491-java.log`.

Local (scratch `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-6\`, script `build-runs.sh`):
- Targeted `WebConfigTest,SalesBatchProcessTest`, reverse alphabetical, fresh `java.io.tmpdir` (the order that failed in Assess): passes; one flake at `SalesBatchProcessTest:71` (task count 0, retried and passed), which is the approval race of card SBM-5, not the report folder (`build-targeted*.log`).
- Full `mvn -B clean test` with random order and retries, fresh `java.io.tmpdir`: 71 run, 0 failed, 2 skipped, BUILD SUCCESS (`build-full.log`). No `sales-batch-test-reports*` folder left behind in the temp folder afterwards (cleanup works); the existing tests' input CSVs remain, as before.
- Acceptance class alone: 4 of 4 pass, about 170 s (`build-accept.log`). In the pipeline it took 106 s.

### Sensitivity checks (`sensitivity.sh`, scratch worktree of de9f5f2, `sensitivity-summary.txt`)

1. Fix reverted (the five test classes as at d13a17e): AC2 fails, seed 7, the same order as before the fix.
2. One assertion in `SalesBatchProcessTest.approvedBatchIsPublished` changed (`report.size()` 3 to 4): AC4 fails naming that method.
3. `runOrder=alphabetical` put back in `ci.yml`: AC3 fails.

AC1 passes before and after the fix (explained in Test design); its guard is the same as AC2's, which check 1 shows is noticed.

### Side effects and decisions

- `LocalDebugServer` with `-Ddebug.inmemory=true` uses `TestInfraConfig`, so its reports now go to the context's temp folder (the path is logged by `PublishDelegate`) and are deleted when it stops, instead of `./reports`. It is a developer helper under `src/test`, not a test; left as it is.
- Mutation testing: the project has none set up; not run.
- Design review by a fresh-context helper (Plan's gap): still not done; this session has no way to start a subagent. The design's risky claim (the bean override takes effect) is confirmed by the passing targeted run and by sensitivity check 1.

### Exit criteria

- The pipeline on the latest commit passes, the acceptance tests unchanged: run 46002534491 on de9f5f2 green; this card-file commit is docs-only and its run is linked on the card's done comment.
- The pull request is ready for review: PR #8 marked ready.

## Build (after review), the card lead, 2026-11-28

The blind reviewer sent the card back with one finding: `ci.yml:35`, the random-order step retries a failed test up to 2 times in the same JVM, so an order dependence that passes on a retry leaves the run green; asked to run without retries, or fail on retry results, and to print the seed.

What I did (commit dcae409):
- Retries kept: they are a person's decision (card SBM-2, decision 2a) for the approval race (risk SBM-3), which card SBM-5 fixes and is not merged yet. Dropping them would make the pipeline red on the race, not on order.
- New step `Java run order, and no retries outside the known race` (`.github/scripts/java_run_order.py`, runs `if: always()`): reads the surefire reports and fails when any test passed only on a retry (`flakyFailure` or `flakyError`) outside the race's two classes, `SalesBatchProcessTest` and `BatchControllerTest`. The script names the constant to empty once SBM-5 is merged.
- The seed: surefire 2.22.2 can neither print nor take a seed (`surefire.runOrder.random.seed` is 3.0.0-M6 and later; bumping the build plugin stays out of this card, as decided in Plan). So the Java step keeps its log, and the new step writes the class order each module used into the job summary, with a command that runs the `workflow-app` classes again in that order in one JVM (the acceptance test's `RunOrderAcceptanceTest$Child`).
- Left as it is: inside the two race classes a retry still hides a failure. The order dependence this card fixed failed all three tries (run 45995634424 on d13a17e) because the cached context does not change between tries; a dependence that clears itself on a retry in those two classes would not be seen until SBM-5 lets the retries go.

Checks (scratch `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-6\`):
- The script on `build-full.log` with no retried test: order printed, exit 0. With a fixture holding a retried test in `SalesBatchProcessTest` only: exit 0; with one more in `WebConfigTest`: exit 1, naming it (`flake-fixture/`). This is the step's sensitivity check.
- The printed reproduction command, run locally for `WebConfigTest SalesBatchProcessTest` (Windows separator): 10 tests, 0 failed (`repro-check.sh`, `repro-check.log`).
- Acceptance tests unchanged since d13a17e; AC3's check reads the same `mvn ... test` line, still one line with `runOrder=random`; SBM-2's AC1 and AC2 checks still match (`mvn -B clean test`, retries 2, no `|| true`).

Pipeline run 46109864131 on dcae409 (https://github.com/pratapsuraj/java-poc1.8/actions/runs/46109864131): all four jobs green. Java 30/0/0, 7/0/2 skipped, 34/0/0; the new step printed the order (`workflow-app`: ProcessSparkJobLauncherTest, BatchControllerUploadTest, WebConfigTest, BatchControllerTest, InfraConfigTest, SalesBatchProcessTest, ... , so `WebConfigTest` ran before `SalesBatchProcessTest`, the order that failed before the fix) and "Tests that passed only on a retry: None." Log: `.prime/tmp/SBM-6/pipeline-46109864131-java.log`.

### Exit criteria

- The pipeline on the latest commit passes, acceptance tests unchanged: run 46109864131 on dcae409 green; this card-file commit is docs-only, its run is linked in the done comment. `git diff --stat d13a17e HEAD` on the acceptance test and its resource is empty.
- The pull request is ready for review: PR #8, ready (not draft).

## Build (after second review), the card lead, 2026-11-28

The blind reviewer's second pass sent the card back with one finding: `java_run_order.py:21`, `KNOWN_RACE_CLASSES` exempted the whole classes `SalesBatchProcessTest` and `BatchControllerTest`, the very classes whose report-folder dependence this card fixes, so a retry there could still hide an order dependence. Asked to exempt only the race, by test method or failure message.

What I did (commit 0505dd6):
- `KNOWN_RACE` is now a list of (class, test method, exception type, full message pattern), one entry for each way the race shows, taken from the race failures seen in SBM-2's and SBM-5's logs (`.prime/tmp/SBM-2/flaky-*.log`, `mvn-baseline.log`, `.prime/tmp/SBM-5/flaky-*.log`):
  - `SalesBatchProcessTest.approvedBatchIsPublished`: `AssertionError` `expected:<1> but was:<0>` (no approval task yet), or `IllegalStateException` `No pending approval for process instance N`.
  - `rejectedBatchIsNotPublished`: `IllegalStateException` `No pending approval ...`.
  - `startReturnsBeforeTheBackgroundStepsFinish`: `AssertionError` `expected:<1> but was:<0>`.
  - `sparkFailureFailsTheRunWithTheReason`: `AssertionError` `expected null, but was:<...>` (run still alive at `FAILED`).
  - `BatchControllerTest.startThenApproveOverRest`: `AssertionError` `Status expected:<200> but was:<409>`; `approvingTwiceIsAConflict`: `IllegalStateException` `No pending approval ...`.
- Every failed try of a retried test is checked (surefire writes one `flakyFailure`/`flakyError` per try; the old script read only the first). A test passes the step only when every failed try matches the race.
- So the report-folder failure (`NoSuchFileException` in `approvedBatchIsPublished`) now fails the step even inside the race's classes. The `ci.yml` comment says so; the Java test command line is unchanged, so AC3 reads the same line.

Checks (`.prime/tmp/SBM-6/race-matcher-check.sh`, log `race-matcher-check.log`; fixtures in `flake-fixture2/`):
- No retried test: exit 0. Every race form above, one test with two race tries: exit 0, each listed as the known race.
- `approvedBatchIsPublished` retried after `NoSuchFileException` on the report (the reviewer's case): exit 1.
- `approvedBatchIsPublished` with one race try and one `expected:<3> but was:<4>` try: exit 1.
- The race's 409 message on a method not in the list (`unknownBatchIs404AndBadDateIs400`): exit 1.
- The two race classes run three times locally with retries and random order (`race-real-check.sh`): the race did not occur, so no real `flaky*` entry to check against; the message texts come from the real logs above, and the step ran clean on the real reports (exit 0, "None.").

### SBM-5 merged meanwhile

Commit 0505dd6 got no pipeline run: PR #8 conflicted with `main`, where card SBM-5 had been merged (0ba05f1). Merged `main` into the branch (a437ff5); the one conflict was the `ci.yml` comment where both cards had removed SBM-2's AC3 step; SBM-6's deletion of that step's script stands (answer to SBM-Q9).

With the race fixed on `main`, the script's own instruction applied: commit 79e3ee2 sets `KNOWN_RACE = []`, so any test that passes only on a retry now fails the step; the race's forms stay in `RACE_BEFORE_SBM5` for record. The retries themselves stay on the `mvn` line (a person's decision on SBM-2); dropping them is for a later card. Fixture check with the empty list (`race-matcher-check-empty.log`): no retry exit 0; every fixture with a retried test, the race forms included, exit 1.

Local, after the merge (`after-merge-full.sh`, `after-merge/full.log`): `mvn -B clean test`, random order, retries 2, fresh `java.io.tmpdir`: BUILD SUCCESS, `common` 33/0/0, `spark-job` 7/0/2 skipped, `workflow-app` 45/0/0 (now including SBM-5's `ApprovalRaceAcceptanceTest` and `AfterCommitTest`). `RunOrderAcceptanceTest` 4 of 4 pass; the step: "None." retried, exit 0.

Observations, not changed by this card:
- `RunOrderAcceptanceTest` now takes about 516 s locally (170 s before): its child JVMs run every `workflow-app` test class, and SBM-5's `ApprovalRaceAcceptanceTest` (about 86 s) is now one of them, four times.
- SBM-5's `ApprovalRaceAcceptanceTest` sets `report.dir=${java.io.tmpdir}/sales-batch-test-reports` with `@TestPropertySource` in its own context, so it does not affect the other classes, but its reports stay in that shared folder between runs (two files left after the local run). Another card's acceptance test; left as it is.

Acceptance tests and their resource unchanged since d13a17e.

Pipeline run 46156464973 on 79e3ee2 (https://github.com/pratapsuraj/java-poc1.8/actions/runs/46156464973): all four jobs green (`java`, `ui`, `acceptance`, `ac4`). Java `common` 33/0/0, `spark-job` 7/0/2 skipped, `workflow-app` 45/0/0; `RunOrderAcceptanceTest` 4 of 4 in 420 s; random order put `DataControllerTest` before `SalesBatchProcessTest`; "Tests that passed only on a retry: None." Log: `.prime/tmp/SBM-6/pipeline-46156464973-java.log`.

### Exit criteria

- The pipeline on the latest commit passes, acceptance tests unchanged: run 46156464973 on 79e3ee2 green; the card-file commit after it is docs-only, its run linked in the done comment.
- The pull request is ready for review: PR #8, ready, no longer conflicting with `main`.

## Verify, Verify, 2026-11-28

Verdict: **SEND BACK to Build** (Ready to build). The fix works, but the random-order acceptance test (AC2) no longer notices when the fix is removed: since `main` (SBM-5) was merged in, none of its three seeds puts the Java test classes in an order that fails without the fix.

Source-blind: I read the pipeline, the test results, the build files, the file list of the change and the card file; not the change's source. Scratch: `C:\Work\javapoc1.8-programme\.prime\tmp\SBM-6\` (`verify-*`).

### The pipeline on the pull request's head

PR #8 head a883023 (`main` at 0ba05f1, unchanged since). Run 46157383511 (https://github.com/pratapsuraj/java-poc1.8/actions/runs/46157383511), on the merge of a883023 into 0ba05f1: all four jobs green (`java`, `ui`, `acceptance`, `ac4`). Java: `common` 33/0/0, `spark-job` 7/0/2 skipped, `workflow-app` 45/0/0; no failure, error or flaky entry in any surefire report (artifact `java-test-reports`); the retry step printed "None.". Red run before the fix: 45995634424 (https://github.com/pratapsuraj/java-poc1.8/actions/runs/45995634424) on d13a17e, AC2 and AC3 failed on all three tries.

| Criterion | Test | Red run 45995634424 | Run 46157383511 on a883023 | Sensitivity (Verify) |
|---|---|---|---|---|
| AC1 | `AC1_javaTestsPassInReverseAlphabeticalOrderFromAFreshTempFolder` | pass (kept behaviour) | pass | **survives**: passes with the fix removed (known since Test design; it relied on AC2) |
| AC2 | `AC2_javaTestsPassInRandomOrderForThreeSeedsFromFreshTempFolders` | fail (seed 7) | pass | **survives**: passes with the fix removed (finding) |
| AC3 | `AC3_pipelineRunsTheJavaTestsInNoFixedOrder` | fail | pass | killed: fails with `runOrder=alphabetical`, and with the order flag dropped |
| AC4 | `AC4_existingTestStepsAndAssertionsAreUnchanged` | pass (kept behaviour) | pass | killed: fails for a changed assertion in `workflow-app` and in `common` |

### Side-by-side: existing tests, `main` against the head (`verify-sweep.sh`, `verify-sbs/summary.txt`)

Fresh clones, `mvn -B clean test -pl workflow-app -am`, no retries, each run with its own empty `java.io.tmpdir`, this card's acceptance class excluded so both sides run the same tests. 8 runs a side: reverse alphabetical, alphabetical, random three times (surefire 2.22.2 has no seed; each run's class order is logged), `WebConfigTest` then `SalesBatchProcessTest`, `SalesBatchProcessTest` then `DataControllerTest`, and the default order under `file.encoding=ISO-8859-1` with the Thai locale (Thai digits).

- Head: 8 of 8 pass (33/0/0 and 41/0/0).
- Main: 4 of 8 pass; the three random runs and the `WebConfigTest`-first pair fail on `SalesBatchProcessTest.approvedBatchIsPublished` (the report-folder dependence), the random runs also on SBM-5's `ApprovalRaceAcceptanceTest.ac3_existingWorkflowTestsPass20RunsInARowWithoutRetries`.
- One group of differences: "main fails in some orders, head passes". It is what the card is for (its criteria), stricter on head's side; no "Known differences" ledger was written in Plan and none is needed for it. No other difference, none in the environment runs.
- Real artifact: the change touches no `src/main`, no `pom.xml` and no properties (`git diff --stat 0ba05f1 a883023`), so the built jars come from the same inputs; not rebuilt.
- Temp folder after the head runs: the per-context report folders are gone; `<tmpdir>/sales-batch-test-reports` stays, written by SBM-5's `ApprovalRaceAcceptanceTest` (risk SBM-10, not this card).

### Sensitivity (`verify-sensitivity.sh`, `verify-sensitivity-m1.sh`, `verify-sens/summary.txt`)

Fresh clone at a883023, no retries:

- M0, no edit: the acceptance class passes, 4 of 4.
- M1, the fix removed (`TestInfraConfig`, `BatchControllerTest`, `BatchControllerUploadTest`, `SalesBatchProcessTest`, `WorkflowServerTest` taken from `main`): **AC1 and AC2 pass** (`m1b-fix-reverted.log`; the first try with a method filter crashed the fork at start and is set aside). The child logs (`v-sens/workflow-app/target/sbm6-run-order/*/child.log`) show why: with SBM-5's two new classes in the list, seed 7 now starts with `WorkflowServerTest` and seed 12 with `SalesBatchProcessTest`, classes that set the report folder themselves, so the shared context is built right and nothing fails. The same seeds failed before the merge (Build's check 1, d13a17e).
- M2, `ci.yml` back to `runOrder=alphabetical`, and M2b, the order flag dropped: AC3 fails. Killed.
- M3 and M4, an assertion changed in `SalesBatchProcessTest` and in `common`'s `HBaseSalesRepositoryTest`: AC4 fails. Killed.

### Finding, for Build

AC2's seeds (7, 12, 19) were picked in Test design so that each order fails without the fix, for the class list of that day. Merging `main` added two classes, the shuffles changed, and none of the three now reaches a failing order: AC2 passes with the fix removed, so it no longer proves AC2. Reproduce: in a copy at a883023, `git checkout 0ba05f1 -- ` the five files above, then `mvn -B test -pl workflow-app -am -DfailIfNoTests=false -Dtest=RunOrderAcceptanceTest`: 4 of 4 pass.

What would hold: AC2 checks, for each seed, that the order puts a class that does not set the report folder (`WebConfigTest`, `DataControllerTest`) before `SalesBatchProcessTest` with no setter before it, and fails loudly ("seed N no longer tests a risky order") if not; or seeds chosen again for today's list with that check kept so the next new class cannot blind it. Then rerun this sensitivity check (M1) and see AC2 fail. Changing an acceptance test after Test design is the builder's call to record; the criterion is unchanged.

### Second verifier

The workflow asks for two verifiers. This session has no way to start a subagent (no agent tool), so no second verifier ran. Gap recorded; the send-back does not depend on it.

### Exit criteria

- Every criterion's test passed in the pipeline on the latest commit: yes (run 46157383511 on a883023); this card-file commit is docs-only.
- The evidence lists criterion, test, run and result, and links the red run: the table above.
- Held back by the sensitivity check: AC2 (and AC1) do not notice the fix being removed.
