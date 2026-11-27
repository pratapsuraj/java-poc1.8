package com.example.salesbatch.workflow;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.salesbatch.common.SalesRepository;
import com.example.salesbatch.workflow.delegate.PublishDelegate;
import com.example.salesbatch.workflow.spark.SparkJobLauncher;

/** Replaces HBase and spark-submit with in-memory fakes. */
@Configuration
public class TestInfraConfig {

    /** Set to true by a test to make the fake Spark job fail. */
    static volatile boolean failSpark = false;

    /** Set by a test to make the fake Spark job slow, so it can check the start request does not wait for it. */
    static volatile long sparkDelayMillis = 0;

    @Bean
    public InMemorySalesRepository salesRepository() {
        return new InMemorySalesRepository();
    }

    @Bean
    public SparkJobLauncher sparkJobLauncher(final InMemorySalesRepository repository) {
        return new SparkJobLauncher() {
            @Override
            public void run(String businessDate) {
                if (sparkDelayMillis > 0) {
                    try {
                        Thread.sleep(sparkDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failSpark) {
                    throw new IllegalStateException("spark-submit exited with code 1");
                }
                repository.aggregateRaw(businessDate);
            }
        };
    }

    /**
     * Card SBM-6: each test context gets its own new, empty report folder, so where reports go no longer depends on
     * which test class happened to build the cached context first (the report.dir system property did).
     */
    @Bean
    public Path testReportDir() throws IOException {
        return Files.createTempDirectory("sales-batch-test-reports");
    }

    /** Deletes the context's report folder when the context closes, so no run leaves reports for the next one. */
    @Bean
    public DisposableBean testReportDirCleanup(final Path testReportDir) {
        return new DisposableBean() {
            @Override
            public void destroy() {
                deleteQuietly(testReportDir);
            }
        };
    }

    /** Replaces the scanned production bean of the same name: writes reports to this context's own folder. */
    @Bean
    public PublishDelegate publishDelegate(SalesRepository repository, Path testReportDir) {
        return new PublishDelegate(repository, testReportDir.toString());
    }

    /** Empties a report folder, keeping the folder itself. */
    static void emptyFolder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                deleteQuietly(p);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort: a folder left behind is new and empty-named per run, so it cannot hide a failure
        }
    }
}
