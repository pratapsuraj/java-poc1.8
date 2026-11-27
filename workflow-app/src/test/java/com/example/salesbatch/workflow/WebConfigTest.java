package com.example.salesbatch.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import com.example.salesbatch.workflow.config.AppConfig;
import com.example.salesbatch.workflow.config.WebConfig;

/** The MVC context (WebConfig) on top of the root context, like WorkflowServer builds it. */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {AppConfig.class, TestInfraConfig.class})
public class WebConfigTest {

    @Autowired ApplicationContext root;
    private MockMvc mvc;

    @Before
    public void setUp() {
        AnnotationConfigWebApplicationContext web = new AnnotationConfigWebApplicationContext();
        web.setParent(root);
        web.setServletContext(new MockServletContext());
        web.register(WebConfig.class);
        web.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(web).build();
    }

    @Test
    public void rootRedirectsToSwaggerUi() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/docs/index.html"));
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/docs/index.html"));
    }

    @Test
    public void openApiSpecAndSwaggerAssetsAreServed() throws Exception {
        mvc.perform(get("/docs/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(content().string(CoreMatchers.containsString("Sales Batch API")));
        mvc.perform(get("/docs/swagger-ui.css")).andExpect(status().isOk());
    }

    @Test
    public void controllersAreRegistered() throws Exception {
        mvc.perform(get("/data/raw").param("date", "20990101")).andExpect(status().isOk()).andExpect(content().string("[]"));
        mvc.perform(get("/batches/unknown-run")).andExpect(status().isNotFound());
    }
}
