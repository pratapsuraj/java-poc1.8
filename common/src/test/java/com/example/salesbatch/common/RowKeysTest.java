package com.example.salesbatch.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RowKeysTest {

    @Test
    public void rawKeyHasSaltDateAndOrderId() {
        String key = RowKeys.raw("20261113", "ORD-42");
        assertTrue(key.matches(RowKeys.rawDateRegex("20261113")));
        assertEquals("20261113", RowKeys.dateOfRaw(key));
        assertTrue(key.endsWith("_20261113_ORD-42"));
    }

    @Test
    public void saltStaysInsideBucketRange() {
        for (int i = 0; i < 1000; i++) {
            int salt = RowKeys.salt("order-" + i);
            assertTrue(salt >= 0 && salt < SalesSchema.SALT_BUCKETS);
        }
    }

    @Test
    public void regexDoesNotMatchOtherDates() {
        String key = RowKeys.raw("20261112", "A1");
        assertTrue(!key.matches(RowKeys.rawDateRegex("20261113")));
    }

    @Test
    public void aggKeyAndRange() {
        String key = RowKeys.agg("20261113", "EU", "P1");
        assertEquals("20261113_EU_P1", key);
        assertTrue(key.compareTo(RowKeys.aggStart("20261113")) >= 0);
        assertTrue(key.compareTo(RowKeys.aggStop("20261113")) < 0);
    }
}
