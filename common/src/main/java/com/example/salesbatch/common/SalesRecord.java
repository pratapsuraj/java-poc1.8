package com.example.salesbatch.common;

import java.math.BigDecimal;

public class SalesRecord {
    private final String date;      // yyyyMMdd
    private final String orderId;
    private final String productId;
    private final String region;
    private final int qty;
    private final BigDecimal amount;

    public SalesRecord(String date, String orderId, String productId, String region, int qty, BigDecimal amount) {
        this.date = date;
        this.orderId = orderId;
        this.productId = productId;
        this.region = region;
        this.qty = qty;
        this.amount = amount;
    }

    public String getDate() { return date; }
    public String getOrderId() { return orderId; }
    public String getProductId() { return productId; }
    public String getRegion() { return region; }
    public int getQty() { return qty; }
    public BigDecimal getAmount() { return amount; }
}
