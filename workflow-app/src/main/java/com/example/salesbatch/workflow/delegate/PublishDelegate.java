package com.example.salesbatch.workflow.delegate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.salesbatch.common.AggRecord;
import com.example.salesbatch.common.SalesRepository;

/** Writes the aggregates of the business date to a CSV report and marks the run COMPLETED. */
@Component("publishDelegate")
public class PublishDelegate implements JavaDelegate {

    private static final Logger LOG = LoggerFactory.getLogger(PublishDelegate.class);

    private final SalesRepository repository;
    private final String reportDir;

    @Autowired
    public PublishDelegate(SalesRepository repository, @Value("${report.dir:/tmp/sales-batch-reports}") String reportDir) {
        this.repository = repository;
        this.reportDir = reportDir;
    }

    @Override
    public void execute(DelegateExecution execution) throws IOException {
        String pid = execution.getProcessInstanceId();
        String date = (String) execution.getVariable("businessDate");
        List<AggRecord> rows = repository.loadAgg(date);

        List<String> lines = new ArrayList<>();
        lines.add("date,region,productId,totalQty,totalAmount,orderCount");
        for (AggRecord r : rows) {
            lines.add(r.getDate() + "," + r.getRegion() + "," + r.getProductId() + ","
                    + r.getTotalQty() + "," + r.getTotalAmount().toPlainString() + "," + r.getOrderCount());
        }
        Path dir = Paths.get(reportDir);
        Files.createDirectories(dir);
        Path report = dir.resolve("report-" + date + ".csv");
        Files.write(report, lines, StandardCharsets.UTF_8);

        execution.setVariable("reportPath", report.toString());
        repository.updateAudit(pid, "COMPLETED", (String) execution.getVariable("approver"));
        LOG.info("Process {}: published {} aggregate rows to {}", pid, rows.size(), report);
    }
}
