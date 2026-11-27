package com.example.salesbatch.workflow.delegate;

import org.activiti.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.salesbatch.common.SalesRepository;

/**
 * Marks a run as failed without throwing. Steps run in the background job executor, which retries a job that
 * throws; a bad CSV or a failed Spark job would just fail again. Instead the step records the reason and sets
 * {@code failed=true}, and the process routes to its failed end event.
 */
final class RunFailure {

    private static final Logger LOG = LoggerFactory.getLogger(RunFailure.class);

    private RunFailure() {}

    static void mark(SalesRepository repository, DelegateExecution execution, String step, Exception e) {
        String pid = execution.getProcessInstanceId();
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        LOG.error("Process {}: step {} failed: {}", pid, step, message, e);
        repository.recordError(pid, message);
        repository.updateAudit(pid, "FAILED", null);
        execution.setVariable("failed", true);
    }
}
