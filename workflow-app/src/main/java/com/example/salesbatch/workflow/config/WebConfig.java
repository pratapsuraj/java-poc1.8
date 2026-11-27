package com.example.salesbatch.workflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.example.salesbatch.workflow.web")
public class WebConfig extends WebMvcConfigurerAdapter {

    /** Needed for POST /batches/upload; the servlet also needs a MultipartConfig (see WorkflowServer). */
    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }

    /** Swagger UI at /docs/: our index.html and openapi.json first, then the Swagger UI webjar assets. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs/**").addResourceLocations(
                "classpath:/swagger/",
                "classpath:/META-INF/resources/webjars/swagger-ui/3.52.5/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/docs/index.html");
        registry.addRedirectViewController("/docs", "/docs/index.html");
    }
}
