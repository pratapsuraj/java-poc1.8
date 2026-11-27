package com.example.salesbatch.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

/** Replaces HBase and spark-submit with in-memory fakes. */
@Configuration
public class TestInfraConfig {

    /** Set to true by a test to make the fake Spark job fail. */
    static volatile boolean failSpark = false;

    /** Set by a test to make the fake Spark job slow, so it can check the start request does not wait for it. */
    static volatile long sparkDelayMillis = 0;

    @Bean
    public InMemorySalesRepository salesRepository() {
        return new InMemorySalesRepository();
    }

    @Bean
    public SparkJobLauncher sparkJobLauncher(final InMemorySalesRepository repository) {
        return new SparkJobLauncher() {
            @Override
            public void run(String businessDate) {
                if (sparkDelayMillis > 0) {
                    try {
                        Thread.sleep(sparkDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failSpark) {
                    throw new IllegalStateException("spark-submit exited with code 1");
                }
                repository.aggregateRaw(businessDate);
            }
        };
    }
}
