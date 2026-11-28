package com.example.salesbatch.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;
import com.example.salesbatch.workflow.web.BatchController;

/**
 * SBM-5 Verify side-by-side harness (scratch only, never committed). Same file is dropped into main and the
 * pull request's head; it records what a client sees through the REST API, aggregated per scenario, to
 * the file named by -Dsbs.out. Uses only public API and existing test infrastructure.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
@TestPropertySource(properties = "activiti.jdbc.url=jdbc:h2:mem:sbs;DB_CLOSE_DELAY=-1")
public class SbsHarnessTest {

    static Path reportDir;
    static final Map<String, Map<String, Integer>> OUT = new TreeMap<>();
    static int day = 0;
    static final int N = Integer.getInteger("sbs.n", 50);
    static final List<String> ORDER = Arrays.asList("QUEUED", "RUNNING", "WAITING_APPROVAL", "COMPLETED", "REJECTED", "FAILED");

    @BeforeClass
    public static void dirs() throws Exception {
        reportDir = Files.createDirectories(Paths.get(System.getProperty("sbs.reports")));
        System.setProperty("report.dir", reportDir.toString());
    }

    @Autowired BatchService batchService;
    @Autowired InMemorySalesRepository repository;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;

    static String nextDate() {
        day++;
        return LocalDate.of(2030, 1, 1).plusDays(day).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    static void record(String scenario, String obs) {
        OUT.computeIfAbsent(scenario, k -> new TreeMap<>()).merge(obs, 1, Integer::sum);
    }

    MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\");
    }

    static String mask(String s, String date) {
        return s == null ? "null" : s.replace(date, "<date>").replaceAll("process instance \\d+", "process instance <id>")
                .replaceAll("\"processInstanceId\":\"\\d+\"", "\"processInstanceId\":\"<id>\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    static String body(MockHttpServletResponse r) throws Exception {
        return r.getStatus() + " " + r.getContentAsString();
    }

    static String jsonField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + field + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    Path csv(List<String> lines) throws Exception {
        Path p = Files.createTempFile("sbs", ".csv");
        Files.write(p, lines, StandardCharsets.UTF_8);
        return p;
    }

    /** One run: start over REST, poll with no sleep, act the moment a decision status shows, settle, record. */
    String run(Path csv, String action, boolean failSpark, long sparkDelay, boolean pollSleeps) throws Exception {
        TestInfraConfig.failSpark = failSpark;
        TestInfraConfig.sparkDelayMillis = sparkDelay;
        MockMvc mvc = mvc();
        String date = nextDate();
        MockHttpServletResponse start = mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"csvPath\":\"" + esc(csv.toString()) + "\",\"businessDate\":\"" + date + "\"}")).andReturn().getResponse();
        if (start.getStatus() != 202) {
            return "start=" + mask(body(start), date);
        }
        String id = jsonField(start.getContentAsString(), "processInstanceId");
        String startStatus = jsonField(start.getContentAsString(), "status");
        List<String> seen = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        String st = null;
        while (System.currentTimeMillis() < deadline) {
            MockHttpServletResponse g = mvc.perform(get("/batches/" + id)).andReturn().getResponse();
            st = jsonField(g.getContentAsString(), "status");
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(st)) {
                seen.add(st);
            }
            if ("WAITING_APPROVAL".equals(st) || "FAILED".equals(st)) {
                break;
            }
            if (pollSleeps) Thread.sleep(50); else Thread.yield();
        }
        StringBuilder o = new StringBuilder();
        o.append("startStatus=").append(startStatus);
        o.append(" decision=").append(st);
        // the moment the decision status is shown: what a client would find
        boolean alive = runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() != null;
        long tasks = taskService.createTaskQuery().processInstanceId(id).count();
        o.append(" atDecision[alive=").append(alive).append(",tasks=").append(tasks).append("]");
        String actCode = "-";
        if ("WAITING_APPROVAL".equals(st) && action != null) {
            MockHttpServletResponse a = mvc.perform(post("/batches/" + id + "/approve")
                    .param("approved", String.valueOf(action.equals("approve"))).param("approver", "sbs")).andReturn().getResponse();
            actCode = mask(body(a), date);
        }
        o.append(" act=").append(actCode);
        // settle
        long settle = System.currentTimeMillis() + 3_000;
        String fin = st;
        while (System.currentTimeMillis() < settle) {
            fin = batchService.status(id);
            boolean ended = runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() == null;
            if (ended || ("WAITING_APPROVAL".equals(fin) && action != null && !actCode.startsWith("200"))) {
                Thread.sleep(300); // allow any after-commit write to land
                fin = batchService.status(id);
                break;
            }
            Thread.sleep(20);
        }
        // a late-arriving status after settle
        Thread.sleep(200);
        String fin2 = batchService.status(id);
        o.append(" final=").append(fin).append(fin2.equals(fin) ? "" : "->" + fin2);
        boolean monotone = true;
        int last = -1;
        for (String s : seen) {
            int ix = ORDER.indexOf(s);
            if (ix < last) monotone = false;
            last = Math.max(last, ix);
        }
        o.append(" pollMonotone=").append(monotone);
        o.append(" error=").append(mask(batchService.error(id), date));
        o.append(" endedAfter=").append(runtimeService.createProcessInstanceQuery().processInstanceId(id).singleResult() == null);
        Path rep = reportDir.resolve("report-" + date + ".csv");
        o.append(" report=").append(Files.exists(rep) ? mask(String.join("|", Files.readAllLines(rep, StandardCharsets.UTF_8)), date) : "none");
        TestInfraConfig.failSpark = false;
        TestInfraConfig.sparkDelayMillis = 0;
        return o.toString();
    }

    static final List<String> GOOD = Arrays.asList("orderId,productId,region,qty,amount", "A1,P1,EU,2,10.50", "A2,P1,EU,3,15.75", "A3,P2,US,1,4.00");

    @Test
    public void sweep() throws Exception {
        Path good = csv(GOOD);
        for (int i = 0; i < N; i++) record("01 approve the moment WAITING_APPROVAL shows (REST, no sleep)", run(good, "approve", false, 0, false));
        for (int i = 0; i < N; i++) record("02 reject the moment WAITING_APPROVAL shows", run(good, "reject", false, 0, false));
        for (int i = 0; i < N; i++) record("03 approve after polling with 50ms sleeps", run(good, "approve", false, 0, true));
        for (int i = 0; i < N; i++) record("04 spark failure", run(good, "approve", true, 0, false));
        Path bad = csv(Arrays.asList("orderId,productId,region,qty,amount", "X1,P1,EU,notanumber,1.00"));
        for (int i = 0; i < N; i++) record("05 bad number in CSV", run(bad, "approve", false, 0, false));
        for (int i = 0; i < 5; i++) record("06 slow spark (300ms), approve at once", run(good, "approve", false, 300, false));

        // input sweep, a few runs each
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        File sd = new File("../sample-data");
        File[] samples = sd.listFiles();
        if (samples != null) {
            Arrays.sort(samples);
            for (File f : samples) inputs.put("sample " + f.getName(), Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        }
        inputs.put("header only", Arrays.asList("orderId,productId,region,qty,amount"));
        inputs.put("zero-byte", new ArrayList<>());
        inputs.put("one row", Arrays.asList("orderId,productId,region,qty,amount", "B1,P1,EU,1,1.00"));
        inputs.put("reordered rows", Arrays.asList("orderId,productId,region,qty,amount", "A3,P2,US,1,4.00", "A2,P1,EU,3,15.75", "A1,P1,EU,2,10.50"));
        inputs.put("rounding ties", Arrays.asList("orderId,productId,region,qty,amount", "R1,P1,EU,1,0.005", "R2,P1,EU,1,0.005", "R3,P1,EU,1,2.345"));
        inputs.put("extra decimals", Arrays.asList("orderId,productId,region,qty,amount", "D1,P1,EU,1,1.123456789"));
        inputs.put("negative qty and amount", Arrays.asList("orderId,productId,region,qty,amount", "N1,P1,EU,-2,-3.50"));
        inputs.put("very large", Arrays.asList("orderId,productId,region,qty,amount", "L1,P1,EU,2147483647,99999999999999.99", "L2,P1,EU,1,0.01"));
        inputs.put("orderId empty", Arrays.asList("orderId,productId,region,qty,amount", ",P1,EU,1,1.00"));
        inputs.put("productId empty", Arrays.asList("orderId,productId,region,qty,amount", "E1,,EU,1,1.00"));
        inputs.put("region empty", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,,1,1.00"));
        inputs.put("qty not a number", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,x,1.00"));
        inputs.put("amount not a number", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,1,y"));
        inputs.put("qty decimal", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,1.5,1.00"));
        inputs.put("missing column", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,1"));
        inputs.put("extra column", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,1,1.00,zz"));
        inputs.put("no header", Arrays.asList("E1,P1,EU,1,1.00"));
        inputs.put("unicode region", Arrays.asList("orderId,productId,region,qty,amount", "U1,P1,Zürich,1,1.00"));
        inputs.put("blank line inside", Arrays.asList("orderId,productId,region,qty,amount", "E1,P1,EU,1,1.00", "", "E2,P1,EU,1,1.00"));
        for (Map.Entry<String, List<String>> e : inputs.entrySet()) {
            Path p = csv(e.getValue());
            for (int i = 0; i < 3; i++) record("10 input: " + e.getKey(), run(p, "approve", false, 0, true));
        }
        Path crlf = Files.createTempFile("sbs", ".csv");
        Files.write(crlf, "orderId,productId,region,qty,amount\r\nC1,P1,EU,1,1.00\r\n".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) record("10 input: CRLF", run(crlf, "approve", false, 0, true));
        Path bom = Files.createTempFile("sbs", ".csv");
        Files.write(bom, "\uFEFForderId,productId,region,qty,amount\nC1,P1,EU,1,1.00\n".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) record("10 input: BOM", run(bom, "approve", false, 0, true));
        for (int i = 0; i < 3; i++) record("10 input: missing file", run(Paths.get("does-not-exist.csv"), "approve", false, 0, true));

        // API edges
        MockMvc mvc = mvc();
        record("20 GET unknown id", body(mvc.perform(get("/batches/nope")).andReturn().getResponse()));
        record("21 approve unknown id", body(mvc.perform(post("/batches/nope/approve")).andReturn().getResponse()));
        record("22 bad date", body(mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON).content("{\"csvPath\":\"x.csv\",\"businessDate\":\"bad\"}")).andReturn().getResponse()));
        record("23 empty csvPath", body(mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON).content("{\"csvPath\":\"\",\"businessDate\":\"20300101\"}")).andReturn().getResponse()));
        record("24 null body fields", body(mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse()));
        for (int i = 0; i < 5; i++) {
            String id = batchService.start(good.toString(), nextDate());
            Await.status(batchService, id, "WAITING_APPROVAL");
            Thread.sleep(100);
            batchService.approve(id, true, "x");
            record("25 approve twice", mask(body(mvc.perform(post("/batches/" + id + "/approve")).andReturn().getResponse()), "@@nodate@@"));
        }
        for (int i = 0; i < 5; i++) {
            TestInfraConfig.sparkDelayMillis = 800;
            String id = batchService.start(good.toString(), nextDate());
            Thread.sleep(150);
            record("26 approve while RUNNING", batchService.status(id) + " " + mask(body(mvc.perform(post("/batches/" + id + "/approve")).andReturn().getResponse()), "@@nodate@@"));
            Await.status(batchService, id, "WAITING_APPROVAL");
            TestInfraConfig.sparkDelayMillis = 0;
            batchService.approve(id, true, "x");
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> e : OUT.entrySet()) {
            sb.append("## ").append(e.getKey()).append('\n');
            for (Map.Entry<String, Integer> o : e.getValue().entrySet()) sb.append(String.format("%4d  %s%n", o.getValue(), o.getKey()));
        }
        Files.write(Paths.get(System.getProperty("sbs.out")), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
