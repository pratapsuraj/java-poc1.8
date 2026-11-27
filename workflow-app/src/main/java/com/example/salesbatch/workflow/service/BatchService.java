package com.example.salesbatch.workflow.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.salesbatch.common.SalesRepository;

@Service
public class BatchService {

    public static final String PROCESS_KEY = "salesBatch";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final SalesRepository repository;

    @Autowired
    public BatchService(RuntimeService runtimeService, TaskService taskService, SalesRepository repository) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.repository = repository;
    }

    /**
     * Starts a run and returns its id at once. Ingest and the Spark job run in the background (job executor);
     * poll {@link #status(String)} until it is WAITING_APPROVAL or FAILED.
     */
    public String start(String csvPath, String businessDate) {
        if (businessDate == null || !businessDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("businessDate must be yyyyMMdd");
        }
        if (csvPath == null || csvPath.trim().isEmpty()) {
            throw new IllegalArgumentException("csvPath is required");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("csvPath", csvPath);
        vars.put("businessDate", businessDate);
        vars.put("failed", false); // read by the gateways after each async step
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY, vars);
        return instance.getId();
    }

    /** Completes the approval task of a run. */
    public void approve(String processInstanceId, boolean approved, String approver) {
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).taskDefinitionKey("approval").list();
        if (tasks.isEmpty()) {
            throw new IllegalStateException("No pending approval for process instance " + processInstanceId);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("approved", approved);
        vars.put("approver", approver == null ? "unknown" : approver);
        taskService.complete(tasks.get(0).getId(), vars);
    }

    /** Audit status (QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED, REJECTED, FAILED) or null if the id is unknown. */
    public String status(String processInstanceId) {
        return repository.auditStatus(processInstanceId);
    }

    /** Why a run failed, or null. */
    public String error(String processInstanceId) {
        return repository.auditError(processInstanceId);
    }
}
