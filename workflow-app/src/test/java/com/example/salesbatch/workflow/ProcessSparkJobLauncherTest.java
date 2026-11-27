package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import com.example.salesbatch.workflow.spark.ProcessSparkJobLauncher;

public class ProcessSparkJobLauncherTest {

    @Test
    public void yarnClusterCommand() throws Exception {
        ProcessSparkJobLauncher launcher = new ProcessSparkJobLauncher(
                "spark-submit", "yarn", "cluster", "a.b.Main", "/jobs/spark-job.jar", "/etc/hbase/conf/hbase-site.xml");
        java.lang.reflect.Method m = ProcessSparkJobLauncher.class.getDeclaredMethod("buildCommand", String.class);
        m.setAccessible(true);
        assertEquals(Arrays.asList("spark-submit", "--class", "a.b.Main", "--master", "yarn", "--deploy-mode", "cluster",
                "--files", "/etc/hbase/conf/hbase-site.xml", "/jobs/spark-job.jar", "20261113"), m.invoke(launcher, "20261113"));
    }

    @Test
    public void localCommandHasNoDeployModeOrFiles() throws Exception {
        ProcessSparkJobLauncher launcher = new ProcessSparkJobLauncher(
                "spark-submit", "local[*]", "cluster", "a.b.Main", "job.jar", "");
        java.lang.reflect.Method m = ProcessSparkJobLauncher.class.getDeclaredMethod("buildCommand", String.class);
        m.setAccessible(true);
        assertEquals(Arrays.asList("spark-submit", "--class", "a.b.Main", "--master", "local[*]", "job.jar", "20261113"),
                m.invoke(launcher, "20261113"));
    }

    @Test(expected = IllegalStateException.class)
    public void missingCommandFailsClearly() {
        new ProcessSparkJobLauncher("/no/such/spark-submit", "local[*]", "client", "a.b.Main", "job.jar", "").run("20261113");
    }
}
