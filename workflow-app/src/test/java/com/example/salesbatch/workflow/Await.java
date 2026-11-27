package com.example.salesbatch.workflow;

import com.example.salesbatch.workflow.service.BatchService;

/** Test helper: runs are asynchronous now, so tests wait for a status instead of assuming it. */
final class Await {

    private Await() {}

    /** Polls until the run has the wanted status; fails the test with the last status after 20 seconds. */
    static void status(BatchService service, String id, String wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        String current = null;
        while (System.currentTimeMillis() < deadline) {
            current = service.status(id);
            if (wanted.equals(current)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run " + id + " did not reach " + wanted + " within 20s, last status: " + current);
    }
}
