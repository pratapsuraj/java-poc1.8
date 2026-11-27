package com.example.salesbatch.common;

import org.apache.hadoop.hbase.util.Bytes;

/** Table, column family and qualifier names shared by the workflow app and the Spark job. */
public final class SalesSchema {
    private SalesSchema() {}

    public static final String RAW_TABLE = "sales_raw";
    public static final String AGG_TABLE = "sales_agg";
    public static final String AUDIT_TABLE = "batch_audit";

    public static final byte[] RAW_CF = Bytes.toBytes("d");
    public static final byte[] AGG_CF = Bytes.toBytes("a");
    public static final byte[] AUDIT_CF = Bytes.toBytes("m");

    // sales_raw columns
    public static final byte[] PRODUCT_ID = Bytes.toBytes("productId");
    public static final byte[] REGION = Bytes.toBytes("region");
    public static final byte[] QTY = Bytes.toBytes("qty");
    public static final byte[] AMOUNT = Bytes.toBytes("amount");
    public static final byte[] TS = Bytes.toBytes("ts");

    // sales_agg columns
    public static final byte[] TOTAL_QTY = Bytes.toBytes("totalQty");
    public static final byte[] TOTAL_AMOUNT = Bytes.toBytes("totalAmount");
    public static final byte[] ORDER_COUNT = Bytes.toBytes("orderCount");

    // batch_audit columns
    public static final byte[] STATUS = Bytes.toBytes("status");
    public static final byte[] STARTED_AT = Bytes.toBytes("startedAt");
    public static final byte[] FINISHED_AT = Bytes.toBytes("finishedAt");
    public static final byte[] APPROVER = Bytes.toBytes("approver");
    public static final byte[] ERROR = Bytes.toBytes("error");

    /** Number of salt buckets, also the number of pre-split regions of sales_raw. */
    public static final int SALT_BUCKETS = 10;
}
