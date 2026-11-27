package com.example.salesbatch.common;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.util.Bytes;

/** HBase 1.4 implementation. Values are stored as UTF-8 strings to keep the dummy app easy to inspect in the shell. */
public class HBaseSalesRepository implements SalesRepository, Closeable {

    private final Connection connection;

    /** Uses hbase-site.xml from the classpath (on EMR: /etc/hbase/conf). */
    public HBaseSalesRepository() throws IOException {
        this(HBaseConfiguration.create());
    }

    public HBaseSalesRepository(Configuration configuration) throws IOException {
        this(ConnectionFactory.createConnection(configuration));
    }

    /** Uses an existing connection (the repository closes it). Also lets tests pass a mocked connection. */
    public HBaseSalesRepository(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    /** Creates the tables if they do not exist yet. */
    public void initSchema() throws IOException {
        HBaseTables.createIfMissing(connection);
    }

    @Override
    public void saveRaw(Collection<SalesRecord> records) {
        List<Put> puts = new ArrayList<>(records.size());
        long now = System.currentTimeMillis();
        for (SalesRecord r : records) {
            Put put = new Put(Bytes.toBytes(RowKeys.raw(r.getDate(), r.getOrderId())));
            put.addColumn(SalesSchema.RAW_CF, SalesSchema.PRODUCT_ID, Bytes.toBytes(r.getProductId()));
            put.addColumn(SalesSchema.RAW_CF, SalesSchema.REGION, Bytes.toBytes(r.getRegion()));
            put.addColumn(SalesSchema.RAW_CF, SalesSchema.QTY, Bytes.toBytes(String.valueOf(r.getQty())));
            put.addColumn(SalesSchema.RAW_CF, SalesSchema.AMOUNT, Bytes.toBytes(r.getAmount().toPlainString()));
            put.addColumn(SalesSchema.RAW_CF, SalesSchema.TS, Bytes.toBytes(String.valueOf(now)));
            puts.add(put);
        }
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.RAW_TABLE))) {
            table.put(puts);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write to " + SalesSchema.RAW_TABLE, e);
        }
    }

    @Override
    public List<SalesRecord> loadRaw(String date) {
        // Row keys are salted (<salt>_<date>_<orderId>), so one date is spread over all salts: filter the scan.
        Scan scan = new Scan();
        scan.addFamily(SalesSchema.RAW_CF);
        scan.setFilter(new RowFilter(CompareFilter.CompareOp.EQUAL, new RegexStringComparator(RowKeys.rawDateRegex(date))));
        List<SalesRecord> out = new ArrayList<>();
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.RAW_TABLE));
             ResultScanner scanner = table.getScanner(scan)) {
            for (Result row : scanner) {
                String[] key = Bytes.toString(row.getRow()).split("_", 3);
                out.add(new SalesRecord(key[1], key[2],
                        str(row, SalesSchema.RAW_CF, SalesSchema.PRODUCT_ID),
                        str(row, SalesSchema.RAW_CF, SalesSchema.REGION),
                        Integer.parseInt(str(row, SalesSchema.RAW_CF, SalesSchema.QTY)),
                        new BigDecimal(str(row, SalesSchema.RAW_CF, SalesSchema.AMOUNT))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SalesSchema.RAW_TABLE, e);
        }
        out.sort(java.util.Comparator.comparing(SalesRecord::getOrderId));
        return out;
    }

    @Override
    public List<AggRecord> loadAgg(String date) {
        Scan scan = new Scan(Bytes.toBytes(RowKeys.aggStart(date)), Bytes.toBytes(RowKeys.aggStop(date)));
        List<AggRecord> out = new ArrayList<>();
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.AGG_TABLE));
             ResultScanner scanner = table.getScanner(scan)) {
            for (Result row : scanner) {
                String[] key = Bytes.toString(row.getRow()).split("_", 3);
                out.add(new AggRecord(key[0], key[1], key[2],
                        Long.parseLong(str(row, SalesSchema.AGG_CF, SalesSchema.TOTAL_QTY)),
                        new BigDecimal(str(row, SalesSchema.AGG_CF, SalesSchema.TOTAL_AMOUNT)),
                        Long.parseLong(str(row, SalesSchema.AGG_CF, SalesSchema.ORDER_COUNT))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SalesSchema.AGG_TABLE, e);
        }
        return out;
    }

    @Override
    public void updateAudit(String processInstanceId, String status, String approver) {
        Put put = new Put(Bytes.toBytes(processInstanceId));
        long now = System.currentTimeMillis();
        put.addColumn(SalesSchema.AUDIT_CF, SalesSchema.STATUS, Bytes.toBytes(status));
        if ("RUNNING".equals(status)) {
            put.addColumn(SalesSchema.AUDIT_CF, SalesSchema.STARTED_AT, Bytes.toBytes(String.valueOf(now)));
        } else if ("COMPLETED".equals(status) || "REJECTED".equals(status) || "FAILED".equals(status)) {
            put.addColumn(SalesSchema.AUDIT_CF, SalesSchema.FINISHED_AT, Bytes.toBytes(String.valueOf(now)));
        }
        if (approver != null) {
            put.addColumn(SalesSchema.AUDIT_CF, SalesSchema.APPROVER, Bytes.toBytes(approver));
        }
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))) {
            table.put(put);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write to " + SalesSchema.AUDIT_TABLE, e);
        }
    }

    @Override
    public String auditStatus(String processInstanceId) {
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))) {
            Result row = table.get(new Get(Bytes.toBytes(processInstanceId)));
            byte[] value = row.getValue(SalesSchema.AUDIT_CF, SalesSchema.STATUS);
            return value == null ? null : Bytes.toString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SalesSchema.AUDIT_TABLE, e);
        }
    }

    @Override
    public void recordError(String processInstanceId, String message) {
        Put put = new Put(Bytes.toBytes(processInstanceId));
        put.addColumn(SalesSchema.AUDIT_CF, SalesSchema.ERROR, Bytes.toBytes(message == null ? "unknown error" : message));
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))) {
            table.put(put);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write to " + SalesSchema.AUDIT_TABLE, e);
        }
    }

    @Override
    public String auditError(String processInstanceId) {
        try (Table table = connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))) {
            Result row = table.get(new Get(Bytes.toBytes(processInstanceId)));
            byte[] value = row.getValue(SalesSchema.AUDIT_CF, SalesSchema.ERROR);
            return value == null ? null : Bytes.toString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SalesSchema.AUDIT_TABLE, e);
        }
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }

    private static String str(Result row, byte[] family, byte[] qualifier) {
        return Bytes.toString(row.getValue(family, qualifier));
    }
}
