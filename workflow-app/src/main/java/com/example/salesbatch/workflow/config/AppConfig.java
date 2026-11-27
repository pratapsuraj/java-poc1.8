package com.example.salesbatch.workflow.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/** Root configuration: engine, delegates and services. Infrastructure beans come from {@link InfraConfig}. */
@Configuration
@Import(EngineConfig.class)
@ComponentScan(basePackages = {"com.example.salesbatch.workflow.delegate", "com.example.salesbatch.workflow.service"})
@PropertySource("classpath:application.properties")
public class AppConfig {

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }
}
