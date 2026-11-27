package com.example.salesbatch.common;

import java.io.IOException;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.util.Bytes;

/** Idempotent creation of the three tables. */
public final class HBaseTables {
    private HBaseTables() {}

    public static void createIfMissing(Connection connection) throws IOException {
        try (Admin admin = connection.getAdmin()) {
            create(admin, SalesSchema.RAW_TABLE, SalesSchema.RAW_CF, splitKeys());
            create(admin, SalesSchema.AGG_TABLE, SalesSchema.AGG_CF, null);
            create(admin, SalesSchema.AUDIT_TABLE, SalesSchema.AUDIT_CF, null);
        }
    }

    /** Nine split points give ten regions, one per salt bucket. */
    static byte[][] splitKeys() {
        byte[][] keys = new byte[SalesSchema.SALT_BUCKETS - 1][];
        for (int i = 1; i < SalesSchema.SALT_BUCKETS; i++) {
            keys[i - 1] = Bytes.toBytes(String.valueOf(i));
        }
        return keys;
    }

    private static void create(Admin admin, String name, byte[] family, byte[][] splits) throws IOException {
        TableName tableName = TableName.valueOf(name);
        if (admin.tableExists(tableName)) {
            return;
        }
        HTableDescriptor descriptor = new HTableDescriptor(tableName);
        descriptor.addFamily(new HColumnDescriptor(family));
        if (splits == null) {
            admin.createTable(descriptor);
        } else {
            admin.createTable(descriptor, splits);
        }
    }
}
