package com.example.salesbatch.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;
import com.example.salesbatch.workflow.web.BatchController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
public class BatchControllerTest {

    @BeforeClass
    public static void reportDirectory() throws Exception {
        System.setProperty("report.dir", Files.createDirectories(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-test-reports")).toString());
    }

    @Autowired BatchService batchService;
    private MockMvc mvc;
    private Path csv;

    @Before
    public void setUp() throws Exception {
        TestInfraConfig.failSpark = false;
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
        csv = Files.createTempFile("sales-web", ".csv");
        Files.write(csv, Collections.singletonList("W1,P9,APAC,4,8.00"), StandardCharsets.UTF_8);
    }

    @Test
    public void startThenApproveOverRest() throws Exception {
        String body = "{\"csvPath\":\"" + csv.toString().replace("\\", "\\\\") + "\",\"businessDate\":\"20261120\"}";
        String response = mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()) // 202: the run continues in the background
                .andReturn().getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(response);
        String id = json.get("processInstanceId").asText();
        Await.status(batchService, id, "WAITING_APPROVAL");

        mvc.perform(get("/batches/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));
        mvc.perform(post("/batches/" + id + "/approve").param("approved", "true").param("approver", "carol"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void unknownBatchIs404AndBadDateIs400() throws Exception {
        mvc.perform(get("/batches/nope")).andExpect(status().isNotFound());
        mvc.perform(post("/batches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"csvPath\":\"x.csv\",\"businessDate\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void approvingTwiceIsAConflict() throws Exception {
        String id = batchService.start(csv.toString(), "20261121");
        Await.status(batchService, id, "WAITING_APPROVAL");
        batchService.approve(id, true, "dave");
        mvc.perform(post("/batches/" + id + "/approve")).andExpect(status().isConflict());
    }
}
