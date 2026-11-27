package com.example.salesbatch.common;

/** Row key builders. Dates are always yyyyMMdd. */
public final class RowKeys {
    private RowKeys() {}

    public static int salt(String orderId) {
        return Math.abs(orderId.hashCode() % SalesSchema.SALT_BUCKETS);
    }

    /** sales_raw: {@code <salt 0-9>_<yyyyMMdd>_<orderId>}. */
    public static String raw(String date, String orderId) {
        return salt(orderId) + "_" + date + "_" + orderId;
    }

    /** Regex matching every sales_raw row key of one business date, whatever the salt. */
    public static String rawDateRegex(String date) {
        return "^[0-9]_" + date + "_.*";
    }

    /** sales_agg: {@code <yyyyMMdd>_<region>_<productId>}. */
    public static String agg(String date, String region, String productId) {
        return date + "_" + region + "_" + productId;
    }

    /** Extracts the date part of a sales_raw row key. */
    public static String dateOfRaw(String rawKey) {
        String[] parts = rawKey.split("_", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Not a sales_raw row key: " + rawKey);
        }
        return parts[1];
    }

    /** Start (inclusive) and stop (exclusive) rows covering one date in sales_agg. */
    public static String aggStart(String date) {
        return date + "_";
    }

    public static String aggStop(String date) {
        return date + "`"; // '`' sorts right after '_'
    }
}
