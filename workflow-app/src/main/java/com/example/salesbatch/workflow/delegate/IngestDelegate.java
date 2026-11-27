package com.example.salesbatch.workflow.delegate;

import java.nio.file.Paths;
import java.util.List;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.salesbatch.common.SalesRecord;
import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.service.CsvSalesParser;

@Component("ingestDelegate")
public class IngestDelegate implements JavaDelegate {

    private static final Logger LOG = LoggerFactory.getLogger(IngestDelegate.class);

    private final SalesRepository repository;

    @Autowired
    public IngestDelegate(SalesRepository repository) {
        this.repository = repository;
    }

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        String pid = execution.getProcessInstanceId();
        String csvPath = (String) execution.getVariable("csvPath");
        String date = (String) execution.getVariable("businessDate");
        repository.updateAudit(pid, "RUNNING", null);
        try {
            List<SalesRecord> records = CsvSalesParser.parse(Paths.get(csvPath), date);
            repository.saveRaw(records);
            execution.setVariable("ingestedRows", records.size());
            LOG.info("Process {}: ingested {} rows for {}", pid, records.size(), date);
        } catch (Exception e) {
            RunFailure.mark(repository, execution, "ingest", e);
        }
    }
}
