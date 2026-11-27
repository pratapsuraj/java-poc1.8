package com.example.salesbatch.workflow;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.salesbatch.common.HBaseSalesRepository;
import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.spark.DockerClusterSparkJobLauncher;
import com.example.salesbatch.workflow.spark.LocalJvmSparkJobLauncher;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

/** IDE debug run: real HBase (docker/local container) and the real Scala job in a local child JVM. */
@Configuration
public class LocalInfraConfig {

    @Bean(initMethod = "initSchema", destroyMethod = "close")
    public SalesRepository salesRepository() throws IOException {
        return new HBaseSalesRepository(); // hbase-site.xml from src/test/resources -> localhost:2181
    }

    /**
     * -Dspark.mode=local-jvm (default): the Scala job runs in a child JVM with local[*], easiest to debug.
     * -Dspark.mode=docker-cluster: it is submitted to the Spark cluster of docker/local/docker-compose.yml.
     */
    @Bean
    public SparkJobLauncher sparkJobLauncher() {
        if ("docker-cluster".equals(System.getProperty("spark.mode", "local-jvm"))) {
            return new DockerClusterSparkJobLauncher(
                    System.getProperty("spark.docker.container", "spark-master"),
                    "spark://spark-master:7077",
                    System.getProperty("spark.docker.jar", "/work/spark-job/target/spark-job-1.0.0-SNAPSHOT.jar"),
                    Integer.getInteger("spark.debug.port", 0),
                    Boolean.getBoolean("spark.debug.suspend"));
        }
        return new LocalJvmSparkJobLauncher(
                System.getProperty("spark.local.classpath.file", "spark-job/target/local-classpath.txt"),
                System.getProperty("spark.local.classes.dir", "spark-job/target/classes"),
                System.getProperty("spark.local.conf.dir", "workflow-app/src/test/resources"),
                Integer.getInteger("spark.debug.port", 0),
                Boolean.getBoolean("spark.debug.suspend"));
    }
}
