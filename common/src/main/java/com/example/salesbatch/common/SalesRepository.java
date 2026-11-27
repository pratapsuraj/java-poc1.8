package com.example.salesbatch.common;

import java.util.Collection;
import java.util.List;

/** Storage abstraction so workflow code can be tested without a running HBase. */
public interface SalesRepository {

    void saveRaw(Collection<SalesRecord> records);

    /** Raw rows loaded from the CSV for one business date (yyyyMMdd), read back from storage. */
    List<SalesRecord> loadRaw(String date);

    /** Aggregates written by the Spark job for one business date (yyyyMMdd). */
    List<AggRecord> loadAgg(String date);

    /** Creates or updates the audit row of a workflow run. */
    void updateAudit(String processInstanceId, String status, String approver);

    /** Current audit status of a workflow run, or null if unknown. */
    String auditStatus(String processInstanceId);

    /** Stores why a run failed, so the API can show it after the (asynchronous) run has ended. */
    void recordError(String processInstanceId, String message);

    /** Reason a run failed, or null if it has not failed. */
    String auditError(String processInstanceId);
}
