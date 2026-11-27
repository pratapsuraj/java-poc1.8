package com.example.salesbatch.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** The HBase connection is mocked; rows are real HBase Result objects, so the parsing is the real code path. */
public class HBaseSalesRepositoryTest {

    private final Connection connection = mock(Connection.class);
    private final Table raw = mock(Table.class);
    private final Table agg = mock(Table.class);
    private final Table audit = mock(Table.class);
    private HBaseSalesRepository repo;

    @Before
    public void setUp() throws IOException {
        when(connection.getTable(TableName.valueOf(SalesSchema.RAW_TABLE))).thenReturn(raw);
        when(connection.getTable(TableName.valueOf(SalesSchema.AGG_TABLE))).thenReturn(agg);
        when(connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))).thenReturn(audit);
        repo = new HBaseSalesRepository(connection);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** A row with the given columns (cells must be sorted by qualifier; a TreeMap does that for ASCII names). */
    private static Result row(String key, byte[] family, String... columnsAndValues) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            sorted.put(columnsAndValues[i], columnsAndValues[i + 1]);
        }
        List<Cell> cells = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            cells.add(new KeyValue(Bytes.toBytes(key), family, Bytes.toBytes(e.getKey()), 1L, Bytes.toBytes(e.getValue())));
        }
        return Result.create(cells);
    }

    private static ResultScanner scannerOf(Result... results) {
        ResultScanner scanner = mock(ResultScanner.class);
        when(scanner.iterator()).thenReturn(Arrays.asList(results).iterator());
        return scanner;
    }

    private static Result rawRow(String key, String product, String region, String qty, String amount) {
        return row(key, SalesSchema.RAW_CF, "productId", product, "region", region, "qty", qty, "amount", amount, "ts", "1");
    }

    // ---- saveRaw -------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void saveRawWritesOnePutPerRecordWithSaltedKeys() throws IOException {
        repo.saveRaw(Arrays.asList(
                new SalesRecord("20261113", "A1", "P1", "EU", 2, new BigDecimal("10.50")),
                new SalesRecord("20261113", "A2", "P2", "US", 1, new BigDecimal("4.00"))));

        ArgumentCaptor<List<Put>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(raw).put(captor.capture());
        List<Put> puts = captor.getValue();
        assertEquals(2, puts.size());
        assertEquals(RowKeys.raw("20261113", "A1"), Bytes.toString(puts.get(0).getRow()));
        assertTrue(puts.get(0).has(SalesSchema.RAW_CF, SalesSchema.QTY, Bytes.toBytes("2")));
        assertTrue(puts.get(0).has(SalesSchema.RAW_CF, SalesSchema.AMOUNT, Bytes.toBytes("10.50")));
        assertTrue(puts.get(1).has(SalesSchema.RAW_CF, SalesSchema.REGION, Bytes.toBytes("US")));
        assertTrue(puts.get(1).has(SalesSchema.RAW_CF, SalesSchema.PRODUCT_ID, Bytes.toBytes("P2")));
    }

    @Test
    public void saveRawFailureIsReported() throws IOException {
        doThrow(new IOException("down")).when(raw).put(anyList());

        try {
            repo.saveRaw(Arrays.asList(new SalesRecord("20261113", "A1", "P1", "EU", 2, BigDecimal.ONE)));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains(SalesSchema.RAW_TABLE));
        }
    }

    // ---- loadRaw -------------------------------------------------------------------------------------------

    @Test
    public void loadRawParsesRowsAndSortsByOrderId() throws IOException {
        ResultScanner scanner = scannerOf(
                rawRow("3_20261113_A2", "P1", "EU", "3", "15.75"),
                rawRow("7_20261113_A1", "P1", "EU", "2", "10.50"));
        when(raw.getScanner(any(Scan.class))).thenReturn(scanner);

        List<SalesRecord> rows = repo.loadRaw("20261113");

        assertEquals(2, rows.size());
        assertEquals("A1", rows.get(0).getOrderId());
        assertEquals("20261113", rows.get(0).getDate());
        assertEquals(2, rows.get(0).getQty());
        assertEquals(new BigDecimal("10.50"), rows.get(0).getAmount());
        assertEquals("A2", rows.get(1).getOrderId());
        assertEquals("EU", rows.get(1).getRegion());

        ArgumentCaptor<Scan> scan = ArgumentCaptor.forClass(Scan.class);
        verify(raw).getScanner(scan.capture());
        assertTrue("the date is selected with a row filter", scan.getValue().getFilter() instanceof RowFilter);
    }

    @Test
    public void loadRawOfAnEmptyDateIsEmpty() throws IOException {
        ResultScanner scanner = scannerOf();
        when(raw.getScanner(any(Scan.class))).thenReturn(scanner);

        assertTrue(repo.loadRaw("20990101").isEmpty());
    }

    @Test(expected = IllegalStateException.class)
    public void loadRawFailureIsReported() throws IOException {
        when(raw.getScanner(any(Scan.class))).thenThrow(new IOException("down"));

        repo.loadRaw("20261113");
    }

    // ---- loadAgg -------------------------------------------------------------------------------------------

    @Test
    public void loadAggParsesAggregatesOfOneDate() throws IOException {
        ResultScanner scanner = scannerOf(
                row("20261113_EU_P1", SalesSchema.AGG_CF, "totalQty", "5", "totalAmount", "26.25", "orderCount", "2"),
                row("20261113_US_P2", SalesSchema.AGG_CF, "totalQty", "1", "totalAmount", "4.00", "orderCount", "1"));
        when(agg.getScanner(any(Scan.class))).thenReturn(scanner);

        List<AggRecord> rows = repo.loadAgg("20261113");

        assertEquals(2, rows.size());
        assertEquals("EU", rows.get(0).getRegion());
        assertEquals("P1", rows.get(0).getProductId());
        assertEquals(5, rows.get(0).getTotalQty());
        assertEquals(new BigDecimal("26.25"), rows.get(0).getTotalAmount());
        assertEquals(2, rows.get(0).getOrderCount());
        assertEquals("US", rows.get(1).getRegion());

        ArgumentCaptor<Scan> scan = ArgumentCaptor.forClass(Scan.class);
        verify(agg).getScanner(scan.capture());
        assertEquals(RowKeys.aggStart("20261113"), Bytes.toString(scan.getValue().getStartRow()));
        assertEquals(RowKeys.aggStop("20261113"), Bytes.toString(scan.getValue().getStopRow()));
    }

    @Test(expected = IllegalStateException.class)
    public void loadAggFailureIsReported() throws IOException {
        when(agg.getScanner(any(Scan.class))).thenThrow(new IOException("down"));

        repo.loadAgg("20261113");
    }

    // ---- audit ---------------------------------------------------------------------------------------------

    private Put auditPut() throws IOException {
        ArgumentCaptor<Put> captor = ArgumentCaptor.forClass(Put.class);
        verify(audit).put(captor.capture());
        return captor.getValue();
    }

    @Test
    public void runningStatusRecordsTheStartTime() throws IOException {
        repo.updateAudit("run1", "RUNNING", null);

        Put put = auditPut();
        assertEquals("run1", Bytes.toString(put.getRow()));
        assertTrue(put.has(SalesSchema.AUDIT_CF, SalesSchema.STATUS, Bytes.toBytes("RUNNING")));
        assertTrue(put.has(SalesSchema.AUDIT_CF, SalesSchema.STARTED_AT));
        assertFalse(put.has(SalesSchema.AUDIT_CF, SalesSchema.FINISHED_AT));
        assertFalse(put.has(SalesSchema.AUDIT_CF, SalesSchema.APPROVER));
    }

    @Test
    public void queuedStatusRecordsNoTimes() throws IOException {
        repo.updateAudit("run1", "QUEUED", null);

        Put put = auditPut();
        assertTrue(put.has(SalesSchema.AUDIT_CF, SalesSchema.STATUS, Bytes.toBytes("QUEUED")));
        assertFalse(put.has(SalesSchema.AUDIT_CF, SalesSchema.STARTED_AT));
        assertFalse(put.has(SalesSchema.AUDIT_CF, SalesSchema.FINISHED_AT));
    }

    @Test
    public void finishedStatusesRecordTheFinishTimeAndApprover() throws IOException {
        for (String status : new String[] {"COMPLETED", "REJECTED", "FAILED"}) {
            Table fresh = mock(Table.class);
            when(connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))).thenReturn(fresh);

            repo.updateAudit("run1", status, "alice");

            ArgumentCaptor<Put> captor = ArgumentCaptor.forClass(Put.class);
            verify(fresh).put(captor.capture());
            Put put = captor.getValue();
            assertTrue(status, put.has(SalesSchema.AUDIT_CF, SalesSchema.STATUS, Bytes.toBytes(status)));
            assertTrue(status, put.has(SalesSchema.AUDIT_CF, SalesSchema.FINISHED_AT));
            assertTrue(status, put.has(SalesSchema.AUDIT_CF, SalesSchema.APPROVER, Bytes.toBytes("alice")));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void updateAuditFailureIsReported() throws IOException {
        doThrow(new IOException("down")).when(audit).put(any(Put.class));

        repo.updateAudit("run1", "RUNNING", null);
    }

    @Test
    public void auditStatusIsReadFromTheAuditRow() throws IOException {
        when(audit.get(any(Get.class))).thenReturn(row("run1", SalesSchema.AUDIT_CF, "status", "WAITING_APPROVAL"));

        assertEquals("WAITING_APPROVAL", repo.auditStatus("run1"));
    }

    @Test
    public void auditStatusOfAnUnknownRunIsNull() throws IOException {
        when(audit.get(any(Get.class))).thenReturn(Result.create(new Cell[0]));

        assertNull(repo.auditStatus("nope"));
    }

    @Test(expected = IllegalStateException.class)
    public void auditStatusFailureIsReported() throws IOException {
        when(audit.get(any(Get.class))).thenThrow(new IOException("down"));

        repo.auditStatus("run1");
    }

    @Test
    public void errorIsStoredAndReadBack() throws IOException {
        repo.recordError("run1", "Line 3: bad number");

        assertTrue(auditPut().has(SalesSchema.AUDIT_CF, SalesSchema.ERROR, Bytes.toBytes("Line 3: bad number")));

        when(audit.get(any(Get.class))).thenReturn(row("run1", SalesSchema.AUDIT_CF, "error", "Line 3: bad number"));
        assertEquals("Line 3: bad number", repo.auditError("run1"));
    }

    @Test
    public void aMissingErrorMessageIsStoredAsUnknown() throws IOException {
        repo.recordError("run1", null);

        assertTrue(auditPut().has(SalesSchema.AUDIT_CF, SalesSchema.ERROR, Bytes.toBytes("unknown error")));
    }

    @Test
    public void auditErrorOfAHealthyRunIsNull() throws IOException {
        when(audit.get(any(Get.class))).thenReturn(row("run1", SalesSchema.AUDIT_CF, "status", "COMPLETED"));

        assertNull(repo.auditError("run1"));
    }

    @Test(expected = IllegalStateException.class)
    public void recordErrorFailureIsReported() throws IOException {
        doThrow(new IOException("down")).when(audit).put(any(Put.class));

        repo.recordError("run1", "boom");
    }

    @Test(expected = IllegalStateException.class)
    public void auditErrorFailureIsReported() throws IOException {
        when(audit.get(any(Get.class))).thenThrow(new IOException("down"));

        repo.auditError("run1");
    }

    // ---- schema and lifecycle --------------------------------------------------------------------------------

    @Test
    public void initSchemaCreatesTheTables() throws IOException {
        Admin admin = mock(Admin.class);
        when(connection.getAdmin()).thenReturn(admin);
        when(admin.tableExists(any(TableName.class))).thenReturn(false);

        repo.initSchema();

        verify(admin).createTable(any(HTableDescriptor.class), any(byte[][].class));
        verify(admin, times(2)).createTable(any(HTableDescriptor.class));
    }

    @Test
    public void closeClosesTheConnection() throws IOException {
        assertSame(connection, repo.connection());

        repo.close();

        verify(connection).close();
    }
}
