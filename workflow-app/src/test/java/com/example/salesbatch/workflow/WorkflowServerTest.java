package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.example.salesbatch.workflow.config.AppConfig;

/**
 * Starts the real embedded Jetty (WorkflowServer.createServer) on a free port with the in-memory fakes and calls it
 * over HTTP, including a real multipart upload. It uses its own in-memory workflow database, so it does not share
 * jobs with the other Spring test contexts.
 */
public class WorkflowServerTest {

    private Server server;
    private int port;

    @Before
    public void startServer() throws Exception {
        System.setProperty("activiti.jdbc.url", "jdbc:h2:mem:workflowservertest;DB_CLOSE_DELAY=-1");
        System.setProperty("report.dir", Files.createDirectories(
                Paths.get(System.getProperty("java.io.tmpdir"), "sales-batch-test-reports")).toString());
        server = WorkflowServer.createServer(0, AppConfig.class, TestInfraConfig.class);
        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @After
    public void stopServer() throws Exception {
        server.stop();
        System.clearProperty("activiti.jdbc.url");
    }

    private static String read(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; in != null && (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private HttpURLConnection open(String path) throws Exception {
        return (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    }

    @Test
    public void serverServesTheOpenApiSpec() throws Exception {
        HttpURLConnection c = open("/docs/openapi.json");

        assertEquals(200, c.getResponseCode());
        assertTrue(read(c).contains("Sales Batch API"));
    }

    @Test
    public void multipartUploadRunsTheWholeFlowOverHttp() throws Exception {
        String boundary = "----test" + System.nanoTime();
        String csv = "orderId,productId,region,qty,amount\nH1,P1,EU,2,10.50\n";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"businessDate\"\r\n\r\n20261204\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"s.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        HttpURLConnection post = open("/batches/upload");
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = post.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(202, post.getResponseCode());
        Matcher m = Pattern.compile("\"processInstanceId\":\"([^\"]+)\"").matcher(read(post));
        assertTrue(m.find());
        String id = m.group(1);

        String status = "";
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !status.contains("WAITING_APPROVAL")) {
            Thread.sleep(100);
            status = read(open("/batches/" + id));
        }
        assertTrue("last status: " + status, status.contains("WAITING_APPROVAL"));

        String agg = read(open("/data/agg?date=20261204"));
        assertTrue(agg, agg.contains("\"region\":\"EU\""));
    }
}
