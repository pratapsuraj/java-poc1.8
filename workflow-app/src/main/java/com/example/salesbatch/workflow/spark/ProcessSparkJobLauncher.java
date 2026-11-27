package com.example.salesbatch.workflow.spark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Launches the job with a local spark-submit process (run the app on the EMR master node). */
public class ProcessSparkJobLauncher implements SparkJobLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessSparkJobLauncher.class);

    private final String submitCommand;
    private final String master;
    private final String deployMode;
    private final String mainClass;
    private final String jar;
    private final String hbaseSite;

    public ProcessSparkJobLauncher(String submitCommand, String master, String deployMode,
                                   String mainClass, String jar, String hbaseSite) {
        this.submitCommand = submitCommand;
        this.master = master;
        this.deployMode = deployMode;
        this.mainClass = mainClass;
        this.jar = jar;
        this.hbaseSite = hbaseSite;
    }

    List<String> buildCommand(String businessDate) {
        List<String> cmd = new ArrayList<>();
        cmd.add(submitCommand);
        cmd.add("--class");
        cmd.add(mainClass);
        cmd.add("--master");
        cmd.add(master);
        if (!master.startsWith("local")) {
            cmd.add("--deploy-mode");
            cmd.add(deployMode);
        }
        if (hbaseSite != null && !hbaseSite.trim().isEmpty()) {
            cmd.add("--files");
            cmd.add(hbaseSite);
        }
        cmd.add(jar);
        cmd.add(businessDate);
        return cmd;
    }

    @Override
    public void run(String businessDate) {
        List<String> cmd = buildCommand(businessDate);
        LOG.info("Starting Spark job: {}", cmd);
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.info("[spark] {}", line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("spark-submit exited with code " + exit);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start spark-submit: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Spark job", e);
        }
    }
}
