package com.example.salesbatch.workflow.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.salesbatch.workflow.service.BatchService;

@RestController
@RequestMapping("/batches")
public class BatchController {

    public static class StartRequest {
        private String csvPath;
        private String businessDate;

        public String getCsvPath() { return csvPath; }
        public void setCsvPath(String csvPath) { this.csvPath = csvPath; }
        public String getBusinessDate() { return businessDate; }
        public void setBusinessDate(String businessDate) { this.businessDate = businessDate; }
    }

    private final BatchService batchService;

    @Autowired
    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<Map<String, String>> start(@RequestBody StartRequest request) {
        String id = batchService.start(request.getCsvPath(), request.getBusinessDate());
        Map<String, String> body = new HashMap<>();
        body.put("processInstanceId", id);
        body.put("status", batchService.status(id));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body); // 202: the run continues in the background
    }

    /** Browser upload: saves the CSV to a temp folder, then runs the same flow as POST /batches. */
    @RequestMapping(value = "/upload", method = RequestMethod.POST, consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> upload(@RequestPart("file") MultipartFile file,
                                                      @RequestParam("businessDate") String businessDate) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        Path dir = Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-uploads"));
        Path saved = dir.resolve(businessDate.replaceAll("\\D", "") + "-" + System.nanoTime() + ".csv");
        Files.copy(file.getInputStream(), saved, StandardCopyOption.REPLACE_EXISTING);
        String id = batchService.start(saved.toString(), businessDate);
        Map<String, String> body = new HashMap<>();
        body.put("processInstanceId", id);
        body.put("status", batchService.status(id));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body); // 202: the run continues in the background
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public ResponseEntity<Map<String, String>> status(@PathVariable("id") String id) {
        String status = batchService.status(id);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, String> body = new HashMap<>();
        body.put("processInstanceId", id);
        body.put("status", status);
        String error = batchService.error(id);
        if (error != null) {
            body.put("error", error); // why the run FAILED (e.g. bad CSV line, Spark exit code)
        }
        return ResponseEntity.ok(body);
    }

    @RequestMapping(value = "/{id}/approve", method = RequestMethod.POST)
    public ResponseEntity<Map<String, String>> approve(@PathVariable("id") String id,
                                                       @RequestParam(value = "approved", defaultValue = "true") boolean approved,
                                                       @RequestParam(value = "approver", defaultValue = "unknown") String approver) {
        batchService.approve(id, approved, approver);
        Map<String, String> body = new HashMap<>();
        body.put("processInstanceId", id);
        body.put("status", batchService.status(id));
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
