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
