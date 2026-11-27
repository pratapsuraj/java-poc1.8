package com.example.salesbatch.workflow.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.salesbatch.common.HBaseSalesRepository;
import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.spark.ProcessSparkJobLauncher;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

/** Real HBase and spark-submit beans. Tests replace this class with in-memory fakes. */
@Configuration
public class InfraConfig {

    @Bean(initMethod = "initSchema", destroyMethod = "close")
    public SalesRepository salesRepository() throws IOException {
        return new HBaseSalesRepository(); // reads hbase-site.xml from the classpath, e.g. /etc/hbase/conf
    }

    @Bean
    public SparkJobLauncher sparkJobLauncher(
            @Value("${spark.submit.command:spark-submit}") String command,
            @Value("${spark.master:yarn}") String master,
            @Value("${spark.deploy.mode:cluster}") String deployMode,
            @Value("${spark.main.class}") String mainClass,
            @Value("${spark.job.jar}") String jar,
            @Value("${spark.hbase.site:}") String hbaseSite) {
        return new ProcessSparkJobLauncher(command, master, deployMode, mainClass, jar, hbaseSite);
    }
}
