package com.example.salesbatch.workflow.config;

import javax.sql.DataSource;

import org.activiti.engine.HistoryService;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.spring.ProcessEngineFactoryBean;
import org.activiti.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Activiti 5 engine on an in-memory H2 database, with the BPMN deployed at startup. */
@Configuration
public class EngineConfig {

    /**
     * In memory H2 by default. To keep runs and pending background jobs across restarts, set for example
     * -Dactiviti.jdbc.url=jdbc:h2:file:./data/activiti;DB_CLOSE_DELAY=-1 or, for Postgres,
     * -Dactiviti.jdbc.url=jdbc:postgresql://localhost:5432/salesbatch_activiti
     * -Dactiviti.jdbc.driver=org.postgresql.Driver -Dactiviti.jdbc.username=... -Dactiviti.jdbc.password=...
     * (the Postgres driver is on the classpath; other databases need their driver added).
     */
    @Bean
    public DataSource dataSource(@Value("${activiti.jdbc.url:jdbc:h2:mem:activiti;DB_CLOSE_DELAY=-1}") String url,
                                 @Value("${activiti.jdbc.driver:org.h2.Driver}") String driver,
                                 @Value("${activiti.jdbc.username:sa}") String username,
                                 @Value("${activiti.jdbc.password:}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driver);
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public SpringProcessEngineConfiguration processEngineConfiguration(DataSource dataSource,
                                                                      PlatformTransactionManager transactionManager) {
        SpringProcessEngineConfiguration config = new SpringProcessEngineConfiguration();
        config.setDataSource(dataSource);
        config.setTransactionManager(transactionManager);
        config.setDatabaseSchemaUpdate("true");
        config.setJobExecutorActivate(true); // runs the async steps (ingest, Spark) in background threads
        config.setDeploymentResources(new Resource[] {new ClassPathResource("processes/sales-batch.bpmn20.xml")});
        return config;
    }

    @Bean
    public ProcessEngineFactoryBean processEngine(SpringProcessEngineConfiguration configuration) {
        ProcessEngineFactoryBean factory = new ProcessEngineFactoryBean();
        factory.setProcessEngineConfiguration(configuration);
        return factory;
    }

    @Bean
    public RuntimeService runtimeService(ProcessEngine engine) {
        return engine.getRuntimeService();
    }

    @Bean
    public TaskService taskService(ProcessEngine engine) {
        return engine.getTaskService();
    }

    @Bean
    public RepositoryService repositoryService(ProcessEngine engine) {
        return engine.getRepositoryService();
    }

    @Bean
    public HistoryService historyService(ProcessEngine engine) {
        return engine.getHistoryService();
    }
}
