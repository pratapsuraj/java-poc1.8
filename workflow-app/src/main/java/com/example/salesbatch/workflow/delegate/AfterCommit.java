package com.example.salesbatch.workflow.delegate;

import org.activiti.engine.impl.cfg.TransactionListener;
import org.activiti.engine.impl.cfg.TransactionState;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.salesbatch.common.SalesRepository;

/**
 * Writes a run's status only once the engine has committed the step it describes.
 *
 * <p>The background steps run inside the job executor's transaction. A status a client acts on
 * ({@code WAITING_APPROVAL}: approve now; {@code FAILED}: the run is over) must not show before the engine has
 * saved the approval task or the run's end, or an approve sent at once finds no task (409). So the write is
 * registered to run after the commit; if the job rolls back, nothing is written and the retried job registers again.
 *
 * <p>After commit the write is retried up to 3 times; if it still fails, the error is logged and the last status
 * stays (the run itself is unaffected). It writes only over {@code RUNNING}, so a late write can never replace a
 * status written after it (for example {@code COMPLETED} by an approve that did not wait for the status).
 */
final class AfterCommit {

    private static final Logger LOG = LoggerFactory.getLogger(AfterCommit.class);

    static final String EXPECTED_BEFORE = "RUNNING";
    static final int RETRIES = 3;
    static final long FIRST_PAUSE_MILLIS = 100;

    private AfterCommit() {}

    /** Called from a delegate inside an engine command: writes {@code status} for the run after the commit. */
    static void status(SalesRepository repository, String processInstanceId, String status) {
        Context.getCommandContext().getTransactionContext()
                .addTransactionListener(TransactionState.COMMITTED, new StatusWrite(repository, processInstanceId, status));
    }

    /** The write itself; holds plain values only, never the execution, which is stale after commit. */
    static final class StatusWrite implements TransactionListener {

        private final SalesRepository repository;
        private final String processInstanceId;
        private final String status;

        StatusWrite(SalesRepository repository, String processInstanceId, String status) {
            this.repository = repository;
            this.processInstanceId = processInstanceId;
            this.status = status;
        }

        @Override
        public void execute(CommandContext commandContext) {
            run();
        }

        /** Never throws: an exception here would reach the job executor after its job has already committed. */
        void run() {
            long pause = FIRST_PAUSE_MILLIS;
            for (int attempt = 0; ; attempt++) {
                try {
                    String current = repository.auditStatus(processInstanceId);
                    if (EXPECTED_BEFORE.equals(current)) {
                        repository.updateAudit(processInstanceId, status, null);
                    } else {
                        LOG.warn("Process {}: status {} not written, the run is already {}", processInstanceId, status, current);
                    }
                    return;
                } catch (RuntimeException e) {
                    if (attempt >= RETRIES) {
                        LOG.error("Process {}: could not write status {} after {} attempts; it stays at its last status",
                                processInstanceId, status, attempt + 1, e);
                        return;
                    }
                    LOG.warn("Process {}: writing status {} failed (attempt {}), retrying: {}",
                            processInstanceId, status, attempt + 1, e.getMessage());
                    if (!pause(pause)) {
                        LOG.error("Process {}: interrupted while retrying status {}; it stays at its last status",
                                processInstanceId, status);
                        return;
                    }
                    pause *= 2;
                }
            }
        }

        private static boolean pause(long millis) {
            try {
                Thread.sleep(millis);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
