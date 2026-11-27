package com.example.salesbatch.workflow.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.salesbatch.common.AggRecord;
import com.example.salesbatch.common.SalesRecord;
import com.example.salesbatch.common.SalesRepository;

/** Read-only views of what is stored in HBase, for the UI. */
@RestController
@RequestMapping("/data")
public class DataController {

    private final SalesRepository repository;

    @Autowired
    public DataController(SalesRepository repository) {
        this.repository = repository;
    }

    /** Rows of the uploaded CSV, read from the sales_raw table. */
    @RequestMapping(value = "/raw", method = RequestMethod.GET)
    public List<SalesRecord> raw(@RequestParam("date") String date) {
        return repository.loadRaw(checkDate(date));
    }

    /** Aggregates written by the Spark job, read from the sales_agg table. */
    @RequestMapping(value = "/agg", method = RequestMethod.GET)
    public List<AggRecord> agg(@RequestParam("date") String date) {
        return repository.loadAgg(checkDate(date));
    }

    private static String checkDate(String date) {
        if (date == null || !date.matches("\\d{8}")) {
            throw new IllegalArgumentException("date must be yyyyMMdd");
        }
        return date;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
