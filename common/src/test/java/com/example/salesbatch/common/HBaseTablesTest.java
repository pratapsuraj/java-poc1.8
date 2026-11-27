package com.example.salesbatch.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;

public class HBaseTablesTest {

    private final Connection connection = mock(Connection.class);
    private final Admin admin = mock(Admin.class);

    @Before
    public void setUp() throws IOException {
        when(connection.getAdmin()).thenReturn(admin);
    }

    @Test
    public void createsAllThreeTablesWhenMissing() throws IOException {
        when(admin.tableExists(any(TableName.class))).thenReturn(false);

        HBaseTables.createIfMissing(connection);

        verify(admin).createTable(any(HTableDescriptor.class), any(byte[][].class)); // sales_raw, pre-split
        verify(admin, times(2)).createTable(any(HTableDescriptor.class));            // sales_agg and batch_audit
        verify(admin).close();
    }

    @Test
    public void doesNothingWhenTablesExist() throws IOException {
        when(admin.tableExists(any(TableName.class))).thenReturn(true);

        HBaseTables.createIfMissing(connection);

        verify(admin, never()).createTable(any(HTableDescriptor.class));
        verify(admin, never()).createTable(any(HTableDescriptor.class), any(byte[][].class));
    }

    @Test
    public void rawTableIsSplitOncePerSaltBucket() {
        byte[][] keys = HBaseTables.splitKeys();

        assertEquals(SalesSchema.SALT_BUCKETS - 1, keys.length);
        assertArrayEquals(Bytes.toBytes("1"), keys[0]);
        assertArrayEquals(Bytes.toBytes("9"), keys[8]);
    }
}
