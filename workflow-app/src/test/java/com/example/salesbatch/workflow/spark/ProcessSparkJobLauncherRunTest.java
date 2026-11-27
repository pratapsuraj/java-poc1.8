package com.example.salesbatch.workflow.spark;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Runs the real process handling of the launcher with a harmless command (the JVM itself) instead of spark-submit. */
public class ProcessSparkJobLauncherRunTest {

    private static String java() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static ProcessSparkJobLauncher launcherRunning(final String... command) {
        return new ProcessSparkJobLauncher("unused", "local[*]", "client", "a.b.Main", "job.jar", "") {
            @Override
            List<String> buildCommand(String businessDate) {
                return Arrays.asList(command);
            }
        };
    }

    @Test
    public void aProcessThatExitsWithZeroIsSuccess() {
        launcherRunning(java(), "-version").run("20261113"); // prints a few lines (logged as [spark]) and exits 0
    }

    @Test
    public void aNonZeroExitCodeFailsTheRun() {
        try {
            launcherRunning(java(), "-nosuchoption").run("20261113");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exited with code"));
        }
    }

    @Test
    public void aCommandThatCannotStartFailsClearly() {
        try {
            launcherRunning("/no/such/command").run("20261113");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Cannot start spark-submit"));
        }
    }
}
