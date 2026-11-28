package com.example.salesbatch.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.common.HBaseSalesRepository;
import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;
import com.example.salesbatch.workflow.web.BatchController;

/**
 * SBM-5 Verify side-by-side harness against the real status store (HBase 1.4 in Docker), scratch only.
 * The Spark step is a fake (no aggregates), so reports are header-only; what is compared is what a
 * client sees of the run's status, the approve answer and the error text.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, SbsHBaseHarnessTest.Infra.class})
@TestPropertySource(properties = "activiti.jdbc.url=jdbc:h2:mem:sbshbase;DB_CLOSE_DELAY=-1")
public class SbsHBaseHarnessTest {

    static volatile boolean failSpark;

    @Configuration
    public static class Infra {
        @Bean(initMethod = "initSchema", destroyMethod = "close")
        public SalesRepository salesRepository() throws IOException {
            return new HBaseSalesRepository();
        }

        @Bean
        public SparkJobLauncher sparkJobLauncher() {
            return businessDate -> {
                if (failSpark) throw new IllegalStateException("spark-submit exited with code 1");
            };
        }
    }

    static Path reportDir;
    static final Map<String, Map<String, Integer>> OUT = new TreeMap<>();
    static int day = 0;
    static final int N = Integer.getInteger("sbs.n", 30);

    @BeforeClass
    public static void fresh() throws Exception {
        reportDir = Files.createDirectories(Paths.get(System.getProperty("sbs.reports")));
        System.setProperty("report.dir", reportDir.toString());
        try (Connection c = ConnectionFactory.createConnection(HBaseConfiguration.create()); Admin admin = c.getAdmin()) {
            for (String t : new String[] {"batch_audit", "sales_raw", "sales_agg"}) {
                TableName n = TableName.valueOf(t);
                if (admin.tableExists(n)) {
                    admin.disableTable(n);
                    admin.deleteTable(n);
                }
            }
        }
    }

    @Autowired BatchService batchService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    static String nextDate() {
        day++;
        return LocalDate.of(2031, 1, 1).plusDays(day).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    static void record(String scenario, String obs) {
        OUT.computeIfAbsent(scenario, k -> new TreeMap<>()).merge(obs, 1, Integer::sum);
    }

    static String mask(String s, String date) {
        return s == null ? "null" : s.replace(date, "<date>").replaceAll("process instance \\d+", "process instance <id>")
                .replaceAll("\"processInstanceId\":\"\\d+\"", "\"processInstanceId\":\"<id>\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    static String jsonField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    String run(Path csv, String action, boolean fail) throws Exception {
        failSpark = fail;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
        String date = nextDate();
        MockHttpServletResponse start = mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"csvPath\":\"" + csv.toString().replace("\\", "\\\\") + "\",\"businessDate\":\"" + date + "\"}")).andReturn().getResponse();
        String id = jsonField(start.getContentAsString(), "processInstanceId");
        String st = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            st = jsonField(mvc.perform(get("/batches/" + id)).andReturn().getResponse().getContentAsString(), "status");
            if ("WAITING_APPROVAL".equals(st) || "FAILED".equals(st)) break;
        }
        StringBuilder o = new StringBuilder("startStatus=" + jsonField(start.getContentAsString(), "status") + " decision=" + st);
        boolean alive = runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() != null;
        o.append(" atDecision[alive=").append(alive).append(",tasks=").append(taskService.createTaskQuery().processInstanceId(id).count()).append("]");
        String act = "-";
        if ("WAITING_APPROVAL".equals(st) && action != null) {
            MockHttpServletResponse a = mvc.perform(post("/batches/" + id + "/approve")
                    .param("approved", String.valueOf(action.equals("approve"))).param("approver", "sbs")).andReturn().getResponse();
            act = mask(a.getStatus() + " " + a.getContentAsString(), date);
        }
        o.append(" act=").append(act);
        long settle = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < settle
                && runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() != null
                && !(act.startsWith("409"))) {
            Thread.sleep(20);
        }
        Thread.sleep(1000); // let any late status write land
        o.append(" final=").append(batchService.status(id));
        o.append(" error=").append(mask(batchService.error(id), date));
        o.append(" endedAfter=").append(runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() == null);
        Path rep = reportDir.resolve("report-" + date + ".csv");
        o.append(" report=").append(Files.exists(rep) ? String.join("|", Files.readAllLines(rep, StandardCharsets.UTF_8)) : "none");
        failSpark = false;
        return o.toString();
    }

    @Test
    public void sweep() throws Exception {
        Path good = Files.createTempFile("sbsh", ".csv");
        Files.write(good, Arrays.asList("orderId,productId,region,qty,amount", "A1,P1,EU,2,10.50", "A3,P2,US,1,4.00"), StandardCharsets.UTF_8);
        Path bad = Files.createTempFile("sbsh", ".csv");
        Files.write(bad, Arrays.asList("orderId,productId,region,qty,amount", "X1,P1,EU,notanumber,1.00"), StandardCharsets.UTF_8);
        for (int i = 0; i < N; i++) record("H1 HBase: approve the moment WAITING_APPROVAL shows", run(good, "approve", false));
        for (int i = 0; i < N; i++) record("H2 HBase: reject the moment WAITING_APPROVAL shows", run(good, "reject", false));
        for (int i = 0; i < N; i++) record("H3 HBase: spark failure", run(good, null, true));
        for (int i = 0; i < N; i++) record("H4 HBase: bad number in CSV", run(bad, null, false));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> e : OUT.entrySet()) {
            sb.append("## ").append(e.getKey()).append('\n');
            for (Map.Entry<String, Integer> o : e.getValue().entrySet()) sb.append(String.format("%4d  %s%n", o.getValue(), o.getKey()));
        }
        Files.write(Paths.get(System.getProperty("sbs.out")), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
