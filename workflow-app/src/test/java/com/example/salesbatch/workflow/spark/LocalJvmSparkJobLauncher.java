package com.example.salesbatch.workflow.spark;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE-only launcher: runs the Scala job (SalesAggregator) in a plain child JVM with spark.master=local[*],
 * so no spark-submit or Spark install is needed. The classpath comes from spark-job/target/local-classpath.txt
 * (see README, "Debug in the IDE"). Set spark.debug.port to open a JDWP port for debugging the Scala code.
 */
public class LocalJvmSparkJobLauncher extends ProcessSparkJobLauncher {

    private final String classpathFile;
    private final String classesDir;
    private final String confDir;
    private final int debugPort;
    private final boolean suspend;

    public LocalJvmSparkJobLauncher(String classpathFile, String classesDir, String confDir, int debugPort, boolean suspend) {
        super("java", "local[*]", "client", "com.example.salesbatch.spark.SalesAggregator", "unused", "");
        this.classpathFile = classpathFile;
        this.classesDir = classesDir;
        this.confDir = confDir;
        this.debugPort = debugPort;
        this.suspend = suspend;
    }

    @Override
    List<String> buildCommand(String businessDate) {
        String deps;
        try {
            deps = new String(Files.readAllBytes(Paths.get(classpathFile)), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Missing " + classpathFile + ". Run: mvn -pl spark-job -am compile "
                    + "dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt", e);
        }
        String classpath = confDir + File.pathSeparator + classesDir + File.pathSeparator + deps;

        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (debugPort > 0) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address=" + debugPort);
        }
        cmd.add("-Dspark.master=local[*]");
        cmd.add("-Dspark.ui.enabled=false");
        cmd.add("-Dspark.driver.host=127.0.0.1");
        cmd.add("-Dspark.driver.bindAddress=127.0.0.1");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("com.example.salesbatch.spark.SalesAggregator");
        cmd.add(businessDate);
        return cmd;
    }
}
