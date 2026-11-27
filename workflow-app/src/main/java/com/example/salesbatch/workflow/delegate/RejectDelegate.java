package com.example.salesbatch.workflow.delegate;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.salesbatch.common.SalesRepository;

@Component("rejectDelegate")
public class RejectDelegate implements JavaDelegate {

    private final SalesRepository repository;

    @Autowired
    public RejectDelegate(SalesRepository repository) {
        this.repository = repository;
    }

    @Override
    public void execute(DelegateExecution execution) {
        repository.updateAudit(execution.getProcessInstanceId(), "REJECTED", (String) execution.getVariable("approver"));
    }
}
