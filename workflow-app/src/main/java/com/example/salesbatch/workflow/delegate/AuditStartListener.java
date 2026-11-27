package com.example.salesbatch.workflow.delegate;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.salesbatch.common.SalesRepository;

/**
 * Runs when a process instance starts, in the same request as startProcessInstance and before the first
 * asynchronous step. It makes the run visible as QUEUED immediately, so GET /batches/{id} never returns 404
 * for a run that was just accepted, and the background job cannot be overtaken by this write.
 */
@Component("auditStartListener")
public class AuditStartListener implements ExecutionListener {

    private final SalesRepository repository;

    @Autowired
    public AuditStartListener(SalesRepository repository) {
        this.repository = repository;
    }

    @Override
    public void notify(DelegateExecution execution) {
        repository.updateAudit(execution.getProcessInstanceId(), "QUEUED", null);
    }
}
