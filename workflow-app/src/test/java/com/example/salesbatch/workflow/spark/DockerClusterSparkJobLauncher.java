package com.example.salesbatch.workflow.spark;

import java.util.ArrayList;
import java.util.List;

/**
 * IDE-only launcher: submits the Scala job to the Spark standalone cluster started by
 * docker/local/docker-compose.yml (1 master, 2 workers). It runs spark-submit inside the spark-master container
 * with docker exec, in client deploy mode: the driver runs in that container and the executors on the workers,
 * and spark-submit stays alive until the job ends, so its exit code tells whether the job succeeded.
 *
 * Set spark.debug.port to open a JDWP port on the driver (published as port 5006 by the compose file).
 * Breakpoints inside Spark tasks (the map and foreachPartition bodies) run in the worker JVMs, not in the driver.
 */
public class DockerClusterSparkJobLauncher extends ProcessSparkJobLauncher {

    private final String container;
    private final String jarInContainer;
    private final int debugPort;
    private final boolean suspend;

    public DockerClusterSparkJobLauncher(String container, String master, String jarInContainer, int debugPort, boolean suspend) {
        super("docker", master, "client", "com.example.salesbatch.spark.SalesAggregator", jarInContainer, "");
        this.container = container;
        this.jarInContainer = jarInContainer;
        this.debugPort = debugPort;
        this.suspend = suspend;
    }

    @Override
    List<String> buildCommand(String businessDate) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add("-i");
        cmd.add(container);
        cmd.add("/opt/spark/bin/spark-submit");
        cmd.add("--class");
        cmd.add("com.example.salesbatch.spark.SalesAggregator");
        cmd.add("--master");
        cmd.add(masterUrl());
        cmd.add("--deploy-mode");
        cmd.add("client");
        cmd.add("--conf");
        cmd.add("spark.driver.host=" + container);      // executors connect back to the driver by this name
        cmd.add("--conf");
        cmd.add("spark.ui.enabled=false");
        cmd.add("--conf");
        cmd.add("spark.executor.memory=512m");
        if (debugPort > 0) {
            cmd.add("--driver-java-options");
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address=" + debugPort);
        }
        cmd.add(jarInContainer);
        cmd.add(businessDate);
        return cmd;
    }

    private String masterUrl() {
        return "spark://spark-master:7077";
    }
}
