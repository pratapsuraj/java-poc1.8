package com.example.salesbatch.workflow.delegate;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

@Component("sparkJobDelegate")
public class SparkJobDelegate implements JavaDelegate {

    private final SparkJobLauncher launcher;
    private final SalesRepository repository;

    @Autowired
    public SparkJobDelegate(SparkJobLauncher launcher, SalesRepository repository) {
        this.launcher = launcher;
        this.repository = repository;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String pid = execution.getProcessInstanceId();
        try {
            launcher.run((String) execution.getVariable("businessDate"));
        } catch (RuntimeException e) {
            RunFailure.mark(repository, execution, "spark", e);
            return;
        }
        repository.updateAudit(pid, "WAITING_APPROVAL", null);
    }
}
