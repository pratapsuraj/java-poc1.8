package com.example.salesbatch.common;

import java.math.BigDecimal;

public class AggRecord {
    private final String date;
    private final String region;
    private final String productId;
    private final long totalQty;
    private final BigDecimal totalAmount;
    private final long orderCount;

    public AggRecord(String date, String region, String productId, long totalQty, BigDecimal totalAmount, long orderCount) {
        this.date = date;
        this.region = region;
        this.productId = productId;
        this.totalQty = totalQty;
        this.totalAmount = totalAmount;
        this.orderCount = orderCount;
    }

    public String getDate() { return date; }
    public String getRegion() { return region; }
    public String getProductId() { return productId; }
    public long getTotalQty() { return totalQty; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public long getOrderCount() { return orderCount; }

    @Override
    public String toString() {
        return date + " " + region + " " + productId + " qty=" + totalQty + " amount=" + totalAmount + " orders=" + orderCount;
    }
}
