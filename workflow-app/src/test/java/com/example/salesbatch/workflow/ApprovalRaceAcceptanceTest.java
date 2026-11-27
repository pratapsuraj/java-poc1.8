package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;
import com.example.salesbatch.workflow.web.BatchController;

/**
 * Acceptance tests for card SBM-5 (the approval race), one or more per criterion, named after it.
 *
 * <p>Runs in its own Spring context on its own in-memory workflow database, so its job executor never takes jobs
 * of the context the other test classes share. Its status store is a probe: the moment a status a client acts on
 * (WAITING_APPROVAL, FAILED) is written, it asks the engine from another thread what a client would see then.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, ApprovalRaceAcceptanceTest.ProbeInfraConfig.class})
@TestPropertySource(properties = {
        "activiti.jdbc.url=jdbc:h2:mem:sbm5probe;DB_CLOSE_DELAY=-1",
        "report.dir=${java.io.tmpdir}/sales-batch-test-reports"})
public class ApprovalRaceAcceptanceTest {

    private static final int RUNS = 30;

    @Autowired BatchService batchService;
    @Autowired ProbeRepository repository;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    private MockMvc mvc;
    private Path goodCsv;
    private Path badCsv;

    @Before
    public void setUp() throws Exception {
        ProbeInfraConfig.failSpark = false;
        repository.engine(taskService, runtimeService);
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
        goodCsv = Files.createTempFile("sbm5-good", ".csv");
        Files.write(goodCsv, Arrays.asList(
                "orderId,productId,region,qty,amount",
                "A1,P1,EU,2,10.50",
                "A2,P2,US,1,4.00"), StandardCharsets.UTF_8);
        badCsv = Files.createTempFile("sbm5-bad", ".csv");
        Files.write(badCsv, Arrays.asList("orderId,productId,region,qty,amount", "X1,P1,EU,notanumber,1.00"),
                StandardCharsets.UTF_8);
    }

    @AfterClass
    public static void stopProbe() {
        ProbeRepository.PROBES.shutdownNow();
        ProbeInfraConfig.failSpark = false;
    }

    // AC1: approving at once after 'waiting for approval' never gets 409.

    @Test
    public void ac1_approveAtOnceAfterWaitingApprovalNeverGets409() throws Exception {
        for (int i = 0; i < RUNS; i++) {
            String id = batchService.start(goodCsv.toString(), "20261201");
            awaitWithoutPause(id, "WAITING_APPROVAL");
            MockHttpServletResponse response = mvc.perform(post("/batches/" + id + "/approve")
                    .param("approved", "true").param("approver", "ac1")).andReturn().getResponse();
            assertEquals("run " + (i + 1) + " of " + RUNS + ": approve sent the moment the run showed WAITING_APPROVAL; body "
                    + response.getContentAsString(), 200, response.getStatus());
            mvc.perform(post("/batches/" + id + "/approve")).andExpect(status().isConflict()); // decided once only
            assertEquals("COMPLETED", batchService.status(id));
        }
    }

    @Test
    public void ac1_approvalTaskExistsWhenWaitingApprovalIsShown() throws Exception {
        for (int i = 0; i < 5; i++) {
            String id = batchService.start(goodCsv.toString(), "20261202");
            awaitWithoutPause(id, "WAITING_APPROVAL");
            Seen seen = repository.seenAt(id, "WAITING_APPROVAL");
            assertNotNull("no probe taken when WAITING_APPROVAL was written for run " + id, seen);
            assertEquals("approval tasks a client could see when WAITING_APPROVAL was written (run " + (i + 1) + ")",
                    1, seen.approvalTasks);
            batchService.approve(id, false, "ac1");
        }
    }

    // AC2: a run shown failed has ended, with no approval task.

    @Test
    public void ac2_sparkFailureShownFailedHasEndedWithNoApprovalTask() throws Exception {
        ProbeInfraConfig.failSpark = true;
        for (int i = 0; i < RUNS / 2; i++) {
            assertFailedRunHasEnded(batchService.start(goodCsv.toString(), "20261203"), i);
        }
    }

    @Test
    public void ac2_badCsvShownFailedHasEndedWithNoApprovalTask() throws Exception {
        for (int i = 0; i < RUNS / 2; i++) {
            assertFailedRunHasEnded(batchService.start(badCsv.toString(), "20261204"), i);
        }
    }

    private void assertFailedRunHasEnded(String id, int i) throws Exception {
        awaitWithoutPause(id, "FAILED");
        assertNull("run " + (i + 1) + " shown FAILED is still alive",
                runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult());
        assertEquals(0, taskService.createTaskQuery().processInstanceId(id).count());
        Seen seen = repository.seenAt(id, "FAILED");
        assertNotNull("no probe taken when FAILED was written for run " + id, seen);
        assertTrue("run " + (i + 1) + " was still alive when FAILED was written", !seen.instanceAlive);
        assertEquals(0, seen.approvalTasks);
        assertNotNull("the failure reason is kept", batchService.error(id));
    }

    // AC3: existing tests unchanged, pass 20 runs in a row without retries; new tests pass.
    // (That the existing tests are unchanged is shown by the diff against main; see docs/cards/SBM-5.md.)

    @Test
    public void ac3_existingWorkflowTestsPass20RunsInARowWithoutRetries() {
        for (int i = 0; i < 20; i++) {
            Result result = JUnitCore.runClasses(SalesBatchProcessTest.class, BatchControllerTest.class);
            List<String> failures = new ArrayList<>();
            for (Failure f : result.getFailures()) {
                failures.add(f.getTestHeader() + ": " + f.getMessage());
            }
            assertTrue("run " + (i + 1) + " of 20 failed: " + failures, result.wasSuccessful());
        }
    }

    // AC4: all else as before, side-by-side with main (the full side-by-side run is in Verify; this keeps the
    // status sequence, the report and the error text of each kind of run as on main).

    @Test
    public void ac4_statusSequencesAreAsBefore() throws Exception {
        ProbeInfraConfig.failSpark = false;
        String approved = batchService.start(goodCsv.toString(), "20261205");
        awaitSettled(approved, "WAITING_APPROVAL");
        batchService.approve(approved, true, "ac4");
        assertEquals(Arrays.asList("QUEUED", "RUNNING", "WAITING_APPROVAL", "COMPLETED"), repository.history(approved));
        Path report = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-test-reports",
                "report-20261205.csv");
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        assertEquals("date,region,productId,totalQty,totalAmount,orderCount", lines.get(0));
        assertTrue(lines.contains("20261205,EU,P1,2,10.50,1"));
        assertTrue(lines.contains("20261205,US,P2,1,4.00,1"));

        String rejected = batchService.start(goodCsv.toString(), "20261206");
        awaitSettled(rejected, "WAITING_APPROVAL");
        batchService.approve(rejected, false, "ac4");
        assertEquals(Arrays.asList("QUEUED", "RUNNING", "WAITING_APPROVAL", "REJECTED"), repository.history(rejected));

        String badCsvRun = batchService.start(badCsv.toString(), "20261207");
        awaitSettled(badCsvRun, "FAILED");
        assertEquals(Arrays.asList("QUEUED", "RUNNING", "FAILED"), repository.history(badCsvRun));
        assertTrue(batchService.error(badCsvRun).contains("bad number"));

        ProbeInfraConfig.failSpark = true;
        String sparkRun = batchService.start(goodCsv.toString(), "20261208");
        awaitSettled(sparkRun, "FAILED");
        assertEquals(Arrays.asList("QUEUED", "RUNNING", "FAILED"), repository.history(sparkRun));
        assertEquals("spark-submit exited with code 1", batchService.error(sparkRun));
    }

    /**
     * Waits for the status and for the engine to have settled there (approval task created, or run ended), so the
     * AC4 test checks what is written, not when: it passes on main and on the fix alike.
     */
    private void awaitSettled(String id, String wanted) throws Exception {
        Await.status(batchService, id, wanted);
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            boolean settled = "FAILED".equals(wanted)
                    ? runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() == null
                    : taskService.createTaskQuery().processInstanceId(id).taskDefinitionKey("approval").count() == 1;
            if (settled) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Run " + id + " did not settle at " + wanted + " within 20s");
    }

    /** Polls as a fast client would: no pause between reads, so it acts the moment the status shows. */
    private void awaitWithoutPause(String id, String wanted) {
        long deadline = System.currentTimeMillis() + 20_000;
        String current = null;
        while (System.currentTimeMillis() < deadline) {
            current = batchService.status(id);
            if (wanted.equals(current)) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Run " + id + " did not reach " + wanted + " within 20s, last status: " + current);
    }

    /** What a client could see in the engine at the moment a status was written. */
    static final class Seen {
        final long approvalTasks;
        final boolean instanceAlive;

        Seen(long approvalTasks, boolean instanceAlive) {
            this.approvalTasks = approvalTasks;
            this.instanceAlive = instanceAlive;
        }
    }

    /** In-memory status store that records each run's statuses and probes the engine when an actionable one is written. */
    static class ProbeRepository extends InMemorySalesRepository {

        static final ExecutorService PROBES = Executors.newCachedThreadPool();

        private final Map<String, List<String>> histories = new ConcurrentHashMap<>();
        private final Map<String, Seen> seen = new ConcurrentHashMap<>();
        private volatile TaskService taskService;
        private volatile RuntimeService runtimeService;

        void engine(TaskService tasks, RuntimeService runtime) {
            this.taskService = tasks;
            this.runtimeService = runtime;
        }

        @Override
        public void updateAudit(final String pid, String status, String approver) {
            histories.computeIfAbsent(pid, k -> Collections.synchronizedList(new ArrayList<String>())).add(status);
            if (("WAITING_APPROVAL".equals(status) || "FAILED".equals(status)) && taskService != null) {
                seen.put(pid + "|" + status, probe(pid));
            }
            super.updateAudit(pid, status, approver);
        }

        /** Asks the engine from another thread, as a client would; a query that cannot answer in 5 s counts as nothing seen. */
        private Seen probe(final String pid) {
            Future<Seen> answer = PROBES.submit(() -> new Seen(
                    taskService.createTaskQuery().processInstanceId(pid).taskDefinitionKey("approval").count(),
                    runtimeService.createProcessInstanceQuery().processInstanceId(pid).singleResult() != null));
            try {
                return answer.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                answer.cancel(true);
                return new Seen(-1, true);
            }
        }

        Seen seenAt(String pid, String status) {
            return seen.get(pid + "|" + status);
        }

        List<String> history(String pid) {
            List<String> h = histories.get(pid);
            return h == null ? Collections.<String>emptyList() : new ArrayList<>(h);
        }
    }

    /** Fakes for HBase and spark-submit, as {@link TestInfraConfig}, with the probe as the status store. */
    @Configuration
    static class ProbeInfraConfig {

        static volatile boolean failSpark = false;

        @Bean
        public ProbeRepository salesRepository() {
            return new ProbeRepository();
        }

        @Bean
        public SparkJobLauncher sparkJobLauncher(final ProbeRepository repository) {
            return businessDate -> {
                if (failSpark) {
                    throw new IllegalStateException("spark-submit exited with code 1");
                }
                repository.aggregateRaw(businessDate);
            };
        }
    }
}
