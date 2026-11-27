package com.example.salesbatch.workflow.delegate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.example.salesbatch.workflow.InMemorySalesRepository;

/** The after-commit status write on its own: the RUNNING guard, retries, and that it never throws. */
public class AfterCommitTest {

    /** Fails its first {@code failures} audit calls, then behaves. */
    static class FlakyRepository extends InMemorySalesRepository {
        int failures;
        int calls;

        FlakyRepository(int failures) {
            this.failures = failures;
        }

        @Override
        public String auditStatus(String processInstanceId) {
            calls++;
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("store down");
            }
            return super.auditStatus(processInstanceId);
        }
    }

    @Test
    public void writesOverRunning() {
        FlakyRepository repo = new FlakyRepository(0);
        repo.updateAudit("p", "RUNNING", null);
        new AfterCommit.StatusWrite(repo, "p", "WAITING_APPROVAL").run();
        assertEquals("WAITING_APPROVAL", repo.auditStatus("p"));
    }

    @Test
    public void neverReplacesALaterStatus() {
        FlakyRepository repo = new FlakyRepository(0);
        repo.updateAudit("p", "COMPLETED", "alice");
        new AfterCommit.StatusWrite(repo, "p", "WAITING_APPROVAL").run();
        assertEquals("COMPLETED", repo.auditStatus("p"));
    }

    @Test
    public void retriesThreeTimesThenWrites() {
        FlakyRepository repo = new FlakyRepository(3);
        repo.updateAudit("p", "RUNNING", null);
        new AfterCommit.StatusWrite(repo, "p", "FAILED").run();
        assertEquals(4, repo.calls);
        assertEquals("FAILED", repo.auditStatus("p"));
    }

    @Test
    public void givesUpAfterThreeRetriesWithoutThrowingAndKeepsTheLastStatus() {
        FlakyRepository repo = new FlakyRepository(10);
        repo.updateAudit("p", "RUNNING", null);
        new AfterCommit.StatusWrite(repo, "p", "FAILED").run(); // must not throw
        assertEquals(4, repo.calls);
        repo.failures = 0;
        assertEquals("RUNNING", repo.auditStatus("p"));
    }
}
