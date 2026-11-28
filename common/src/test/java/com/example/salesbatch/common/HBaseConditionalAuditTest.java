package com.example.salesbatch.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** The conditional audit write (SBM-5): one HBase check-and-put on the status, never a read then a put. */
public class HBaseConditionalAuditTest {

    private final Connection connection = mock(Connection.class);
    private final Table audit = mock(Table.class);
    private HBaseSalesRepository repo;

    @Before
    public void setUp() throws IOException {
        when(connection.getTable(TableName.valueOf(SalesSchema.AUDIT_TABLE))).thenReturn(audit);
        repo = new HBaseSalesRepository(connection);
    }

    @Test
    public void writesWithOneCheckAndPutOnTheExpectedStatus() throws IOException {
        when(audit.checkAndPut(any(byte[].class), any(byte[].class), any(byte[].class), any(byte[].class), any(Put.class)))
                .thenReturn(true);

        assertTrue(repo.updateAuditIfStatus("run1", "RUNNING", "FAILED", null));

        ArgumentCaptor<Put> put = ArgumentCaptor.forClass(Put.class);
        verify(audit).checkAndPut(eq(Bytes.toBytes("run1")), eq(SalesSchema.AUDIT_CF), eq(SalesSchema.STATUS),
                eq(Bytes.toBytes("RUNNING")), put.capture());
        assertArrayEquals(Bytes.toBytes("run1"), put.getValue().getRow());
        assertEquals("FAILED", Bytes.toString(put.getValue().get(SalesSchema.AUDIT_CF, SalesSchema.STATUS).get(0).getValue()));
        assertTrue(put.getValue().has(SalesSchema.AUDIT_CF, SalesSchema.FINISHED_AT));
        verify(audit, never()).put(any(Put.class));
        verify(audit, never()).get(any(org.apache.hadoop.hbase.client.Get.class));
    }

    @Test
    public void reportsNoWriteWhenTheStatusHasMovedOn() throws IOException {
        when(audit.checkAndPut(any(byte[].class), any(byte[].class), any(byte[].class), any(byte[].class), any(Put.class)))
                .thenReturn(false);

        assertFalse(repo.updateAuditIfStatus("run1", "RUNNING", "WAITING_APPROVAL", null));
        verify(audit, never()).put(any(Put.class));
    }

    @Test(expected = IllegalStateException.class)
    public void aFailedCheckAndPutIsReported() throws IOException {
        when(audit.checkAndPut(any(byte[].class), any(byte[].class), any(byte[].class), any(byte[].class), any(Put.class)))
                .thenThrow(new IOException("region server down"));

        repo.updateAuditIfStatus("run1", "RUNNING", "FAILED", null);
    }
}
