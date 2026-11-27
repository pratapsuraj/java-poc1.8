package com.example.salesbatch.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.salesbatch.common.SalesRecord;
import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.web.DataController;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
public class DataControllerTest {

    @Autowired InMemorySalesRepository repository;
    private MockMvc mvc;

    @Before
    public void setUp() {
        repository.clear();
        mvc = MockMvcBuilders.standaloneSetup(new DataController(repository)).build();
    }

    @Test
    public void returnsRawAndAggregatedRowsOfTheDate() throws Exception {
        repository.saveRaw(Collections.singletonList(new SalesRecord("20261201", "D1", "P1", "EU", 2, new BigDecimal("9.50"))));
        repository.aggregateRaw("20261201");

        mvc.perform(get("/data/raw").param("date", "20261201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("D1"))
                .andExpect(jsonPath("$[0].amount").value(9.5));
        mvc.perform(get("/data/agg").param("date", "20261201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("EU"))
                .andExpect(jsonPath("$[0].totalQty").value(2));
        mvc.perform(get("/data/raw").param("date", "20261202"))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    public void rejectsBadDate() throws Exception {
        mvc.perform(get("/data/raw").param("date", "bad")).andExpect(status().isBadRequest());
    }
}
