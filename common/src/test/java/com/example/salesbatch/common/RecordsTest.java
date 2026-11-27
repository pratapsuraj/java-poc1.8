package com.example.salesbatch.common;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;

import org.junit.Test;

public class RecordsTest {

    @Test
    public void salesRecordExposesItsFields() {
        SalesRecord r = new SalesRecord("20261113", "A1", "P1", "EU", 2, new BigDecimal("10.50"));

        assertEquals("20261113", r.getDate());
        assertEquals("A1", r.getOrderId());
        assertEquals("P1", r.getProductId());
        assertEquals("EU", r.getRegion());
        assertEquals(2, r.getQty());
        assertEquals(new BigDecimal("10.50"), r.getAmount());
    }

    @Test
    public void aggRecordExposesItsFieldsAndPrintsReadably() {
        AggRecord a = new AggRecord("20261113", "EU", "P1", 5, new BigDecimal("26.25"), 2);

        assertEquals("20261113", a.getDate());
        assertEquals("EU", a.getRegion());
        assertEquals("P1", a.getProductId());
        assertEquals(5, a.getTotalQty());
        assertEquals(new BigDecimal("26.25"), a.getTotalAmount());
        assertEquals(2, a.getOrderCount());
        assertEquals("20261113 EU P1 qty=5 amount=26.25 orders=2", a.toString());
    }
}
