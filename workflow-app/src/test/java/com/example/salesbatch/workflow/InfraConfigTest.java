package com.example.salesbatch.workflow;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.Closeable;

import org.junit.Test;

import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.config.InfraConfig;
import com.example.salesbatch.workflow.spark.ProcessSparkJobLauncher;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

/** The production wiring of the infrastructure beans (the tests elsewhere replace this class with fakes). */
public class InfraConfigTest {

    @Test
    public void sparkLauncherIsTheSparkSubmitProcessLauncher() {
        SparkJobLauncher launcher = new InfraConfig().sparkJobLauncher(
                "spark-submit", "yarn", "cluster", "a.b.Main", "job.jar", "/etc/hbase/conf/hbase-site.xml");

        assertTrue(launcher instanceof ProcessSparkJobLauncher);
    }

    @Test
    public void repositoryIsCreatedWithoutTouchingHBase() throws Exception {
        // The HBase client connects lazily, so creating the repository needs no running HBase.
        SalesRepository repository = new InfraConfig().salesRepository();

        assertNotNull(repository);
        ((Closeable) repository).close();
    }
}
