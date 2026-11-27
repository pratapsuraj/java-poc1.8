package com.example.salesbatch.workflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.example.salesbatch.common.AggRecord;
import com.example.salesbatch.common.SalesRecord;
import com.example.salesbatch.common.SalesRepository;

/** Test double that keeps everything in memory. */
public class InMemorySalesRepository implements SalesRepository {

    final List<SalesRecord> raw = new ArrayList<>();
    final List<AggRecord> agg = new ArrayList<>();
    final Map<String, String> audit = new ConcurrentHashMap<>();
    final Map<String, String> errors = new ConcurrentHashMap<>();

    synchronized void clear() {
        raw.clear();
        agg.clear();
        audit.clear();
        errors.clear();
    }

    @Override
    public void recordError(String processInstanceId, String message) {
        errors.put(processInstanceId, message == null ? "unknown error" : message);
    }

    @Override
    public String auditError(String processInstanceId) {
        return errors.get(processInstanceId);
    }

    @Override
    public synchronized void saveRaw(Collection<SalesRecord> records) {
        raw.addAll(records);
    }

    @Override
    public synchronized List<SalesRecord> loadRaw(String date) {
        List<SalesRecord> out = new ArrayList<>();
        for (SalesRecord r : raw) {
            if (r.getDate().equals(date)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public synchronized List<AggRecord> loadAgg(String date) {
        List<AggRecord> out = new ArrayList<>();
        for (AggRecord a : agg) {
            if (a.getDate().equals(date)) {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public void updateAudit(String processInstanceId, String status, String approver) {
        audit.put(processInstanceId, status);
    }

    @Override
    public String auditStatus(String processInstanceId) {
        return audit.get(processInstanceId);
    }

    /** What the Spark job does, in memory: group raw rows by date, region and product. */
    synchronized void aggregateRaw(String date) {
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (SalesRecord r : raw) {
            if (!r.getDate().equals(date)) {
                continue;
            }
            String key = r.getDate() + "|" + r.getRegion() + "|" + r.getProductId();
            long[] c = counts.computeIfAbsent(key, k -> new long[2]);
            c[0] += r.getQty();
            c[1] += 1;
            amounts.merge(key, r.getAmount(), BigDecimal::add);
        }
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            String[] k = e.getKey().split("\\|");
            agg.add(new AggRecord(k[0], k[1], k[2], e.getValue()[0], amounts.get(e.getKey()), e.getValue()[1]));
        }
    }
}
