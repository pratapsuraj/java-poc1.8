package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.fileUpload;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.service.BatchService;
import com.example.salesbatch.workflow.web.BatchController;
import com.fasterxml.jackson.databind.ObjectMapper;

/** POST /batches/upload and the failure details exposed by GET /batches/{id}. */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
public class BatchControllerUploadTest {

    @BeforeClass
    public static void reportDirectory() throws Exception {
        System.setProperty("report.dir", Files.createDirectories(
                Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-test-reports")).toString());
    }

    @Autowired BatchService batchService;
    @Autowired InMemorySalesRepository repository;
    private MockMvc mvc;

    @Before
    public void setUp() {
        TestInfraConfig.failSpark = false;
        repository.clear();
        mvc = MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "sales.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private String idOf(String json) throws Exception {
        return new ObjectMapper().readTree(json).get("processInstanceId").asText();
    }

    @Test
    public void uploadedFileIsAcceptedAndProcessedInTheBackground() throws Exception {
        String response = mvc.perform(fileUpload("/batches/upload")
                        .file(csv("orderId,productId,region,qty,amount\nU1,P1,EU,2,10.50\n"))
                        .param("businessDate", "20261201"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processInstanceId").exists())
                .andReturn().getResponse().getContentAsString();

        Await.status(batchService, idOf(response), "WAITING_APPROVAL");

        assertEquals(1, repository.raw.size());
        assertEquals("U1", repository.raw.get(0).getOrderId());
        assertEquals("20261201", repository.raw.get(0).getDate());
        assertEquals(1, repository.agg.size());
    }

    @Test
    public void emptyFileIsRejected() throws Exception {
        mvc.perform(fileUpload("/batches/upload").file(csv("")).param("businessDate", "20261201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file is empty"));
    }

    @Test
    public void badBusinessDateIsRejected() throws Exception {
        mvc.perform(fileUpload("/batches/upload").file(csv("orderId,productId,region,qty,amount\nU1,P1,EU,2,10.50\n"))
                        .param("businessDate", "2026-12-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void failedRunExposesItsReasonOverRest() throws Exception {
        String response = mvc.perform(fileUpload("/batches/upload")
                        .file(csv("orderId,productId,region,qty,amount\nX1,P1,EU,notanumber,1.00\n"))
                        .param("businessDate", "20261202"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);

        Await.status(batchService, id, "FAILED");

        mvc.perform(get("/batches/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.CoreMatchers.containsString("bad number")));
    }

    @Test
    public void healthyRunHasNoErrorField() throws Exception {
        String response = mvc.perform(fileUpload("/batches/upload")
                        .file(csv("orderId,productId,region,qty,amount\nU2,P1,EU,1,1.00\n"))
                        .param("businessDate", "20261203"))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(response);
        Await.status(batchService, id, "WAITING_APPROVAL");

        mvc.perform(get("/batches/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
