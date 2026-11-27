package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
public class SalesBatchProcessTest {

    static Path reportDir;

    @BeforeClass
    public static void reportDirectory() throws Exception {
        // Same directory in every test class: the Spring context is cached and report.dir is read only once.
        reportDir = Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-test-reports"));
        System.setProperty("report.dir", reportDir.toString());
    }

    @Autowired BatchService batchService;
    @Autowired InMemorySalesRepository repository;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    private Path csv;

    @Before
    public void sampleCsv() throws Exception {
        TestInfraConfig.failSpark = false;
        repository.clear();
        csv = Files.createTempFile("sales", ".csv");
        Files.write(csv, java.util.Arrays.asList(
                "orderId,productId,region,qty,amount",
                "A1,P1,EU,2,10.50",
                "A2,P1,EU,3,15.75",
                "A3,P2,US,1,4.00"), StandardCharsets.UTF_8);
    }

    @After
    public void clean() {
        TestInfraConfig.failSpark = false;
        TestInfraConfig.sparkDelayMillis = 0;
    }

    @Test
    public void approvedBatchIsPublished() throws Exception {
        String id = batchService.start(csv.toString(), "20261113");
        Await.status(batchService, id, "WAITING_APPROVAL");

        assertEquals("WAITING_APPROVAL", batchService.status(id));
        assertEquals(3, repository.raw.size());
        assertEquals(1, taskService.createTaskQuery().processInstanceId(id).count());

        batchService.approve(id, true, "alice");

        assertEquals("COMPLETED", batchService.status(id));
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult());
        List<String> report = Files.readAllLines(Paths.get(reportDir.toString(), "report-20261113.csv"), StandardCharsets.UTF_8);
        assertEquals("date,region,productId,totalQty,totalAmount,orderCount", report.get(0));
        assertTrue(report.contains("20261113,EU,P1,5,26.25,2"));
        assertTrue(report.contains("20261113,US,P2,1,4.00,1"));
        assertEquals(3, report.size());
    }

    @Test
    public void rejectedBatchIsNotPublished() throws Exception {
        String id = batchService.start(csv.toString(), "20261114");
        Await.status(batchService, id, "WAITING_APPROVAL");

        batchService.approve(id, false, "bob");

        assertEquals("REJECTED", batchService.status(id));
        assertTrue(!Files.exists(Paths.get(reportDir.toString(), "report-20261114.csv")));
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult());
    }

    @Test
    public void sparkFailureFailsTheRunWithTheReason() throws Exception {
        TestInfraConfig.failSpark = true;

        String id = batchService.start(csv.toString(), "20261115"); // does not throw: the run is asynchronous
        Await.status(batchService, id, "FAILED");

        assertTrue(batchService.error(id).contains("spark-submit"));
        assertEquals(0, taskService.createTaskQuery().processInstanceId(id).count()); // never reached approval
        assertNull(runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult()); // run has ended
    }

    @Test
    public void badCsvFailsTheRunBeforeSparkStarts() throws Exception {
        Files.write(csv, java.util.Arrays.asList("orderId,productId,region,qty,amount", "X1,P1,EU,notanumber,1.00"),
                StandardCharsets.UTF_8);

        String id = batchService.start(csv.toString(), "20261116");
        Await.status(batchService, id, "FAILED");

        assertTrue(batchService.error(id).contains("bad number"));
        assertEquals(0, repository.agg.size()); // the Spark step did not run
    }

    @Test
    public void startReturnsBeforeTheBackgroundStepsFinish() throws Exception {
        TestInfraConfig.sparkDelayMillis = 1500;

        long begin = System.currentTimeMillis();
        String id = batchService.start(csv.toString(), "20261117");
        long took = System.currentTimeMillis() - begin;

        assertTrue("start took " + took + " ms, it should not wait for the 1.5 s Spark step", took < 1000);
        String early = batchService.status(id);
        assertTrue("unexpected early status " + early, "QUEUED".equals(early) || "RUNNING".equals(early));

        Await.status(batchService, id, "WAITING_APPROVAL"); // the background job executor finishes the run
        assertEquals(1, taskService.createTaskQuery().processInstanceId(id).count());
    }

    @Test(expected = IllegalStateException.class)
    public void approvingUnknownRunFails() {
        batchService.approve("does-not-exist", true, "alice");
    }

    @Test(expected = IllegalArgumentException.class)
    public void badBusinessDateIsRejected() {
        batchService.start(csv.toString(), "2026-11-13");
    }
}
