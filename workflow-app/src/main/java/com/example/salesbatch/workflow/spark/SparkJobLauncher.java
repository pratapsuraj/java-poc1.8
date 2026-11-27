package com.example.salesbatch.workflow.spark;

/** Runs the Spark aggregation for one business date and returns when it has finished. */
public interface SparkJobLauncher {

    /** @throws IllegalStateException if the job fails */
    void run(String businessDate);
}
