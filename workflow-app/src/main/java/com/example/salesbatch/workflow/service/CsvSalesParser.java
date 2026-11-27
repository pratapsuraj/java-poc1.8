package com.example.salesbatch.workflow.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.salesbatch.common.SalesRecord;

/** Parses lines of {@code orderId,productId,region,qty,amount}. A header line starting with "orderId" is skipped. */
public final class CsvSalesParser {
    private CsvSalesParser() {}

    public static List<SalesRecord> parse(Path file, String businessDate) throws IOException {
        List<SalesRecord> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("orderId")) {
                    continue;
                }
                String[] f = line.split(",");
                if (f.length != 5) {
                    throw new IllegalArgumentException("Line " + lineNo + ": expected 5 fields but found " + f.length);
                }
                try {
                    out.add(new SalesRecord(businessDate, f[0].trim(), f[1].trim(), f[2].trim(),
                            Integer.parseInt(f[3].trim()), new BigDecimal(f[4].trim())));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Line " + lineNo + ": bad number (" + e.getMessage() + ")", e);
                }
            }
        }
        return out;
    }
}
