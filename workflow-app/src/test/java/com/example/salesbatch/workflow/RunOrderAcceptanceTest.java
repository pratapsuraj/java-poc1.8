package com.example.salesbatch.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * Card SBM-6: the Java tests are independent of the order they run in. Criteria AC1 to AC4 are a person's (SBM-Q8).
 *
 * AC1 and AC2 run every workflow-app test class in a child JVM, in a chosen order, with java.io.tmpdir set to a new
 * empty folder (Spring caches its test context per JVM, so only a fresh JVM shows which class builds it first). The
 * child retries a failed test up to 2 times, as the pipeline does for the known approval race (card SBM-5); a test
 * that fails because of the order fails every time, because the cached context does not change between tries.
 */
public class RunOrderAcceptanceTest {

    /**
     * Before SBM-6 the shared Spring test context took its report folder from the report.dir system property, which
     * some classes set before the context was built and these two did not. An order fails without the fix when one
     * of them builds that context first, before SalesBatchProcessTest reads its report.
     */
    static final List<String> SET_NO_REPORT_FOLDER = java.util.Arrays.asList("WebConfigTest", "DataControllerTest");

    /** Not run by Spring's runner, but set report.dir JVM-wide before SBM-6, so it counts as a class that set it. */
    static final String SETS_REPORT_FOLDER_WITHOUT_SPRING_RUNNER = "WorkflowServerTest";

    /** AC2 takes the first three seeds from 1 upward whose order is a risky one (see riskyOrder), up to this one. */
    static final long LAST_SEED = 1000L;

    static final int SEED_COUNT = 3;

    static final int RETRIES = 2;

    @Test
    public void AC1_javaTestsPassInReverseAlphabeticalOrderFromAFreshTempFolder() throws Exception {
        List<String> classes = testClasses();
        Collections.reverse(classes);
        runInChildJvm("AC1 reverse alphabetical", classes);
    }

    @Test
    public void AC2_javaTestsPassInRandomOrderForThreeSeedsFromFreshTempFolders() throws Exception {
        for (long seed : riskySeeds(testClasses())) {
            List<String> classes = testClasses();
            Collections.shuffle(classes, new Random(seed));
            runInChildJvm("AC2 random, seed " + seed, classes);
        }
    }

    /**
     * The first SEED_COUNT seeds from 1 upward whose shuffle of the given classes is a risky order, so that AC2 fails
     * without the fix whatever classes are added later. Seeds picked once by hand stopped doing so when card SBM-5
     * added two classes (Verify's finding on SBM-6).
     */
    static List<Long> riskySeeds(List<String> sorted) throws Exception {
        List<Long> seeds = new ArrayList<>();
        for (long seed = 1; seed <= LAST_SEED && seeds.size() < SEED_COUNT; seed++) {
            List<String> order = new ArrayList<>(sorted);
            Collections.shuffle(order, new Random(seed));
            if (riskyOrder(order)) {
                seeds.add(seed);
            }
        }
        assertEquals("AC2 fewer than " + SEED_COUNT + " seeds in 1.." + LAST_SEED + " shuffle " + sorted
                + " into a risky order, so AC2 would not test one", SEED_COUNT, seeds.size());
        System.out.println("SBM-6 AC2 seeds: " + seeds);
        return seeds;
    }

    /**
     * True when the first class to touch the report folder is one of SET_NO_REPORT_FOLDER: the first class run by
     * Spring's runner or setting report.dir, so the first that could build the shared context. Any other Spring class,
     * a new one included, counts as one that sets the folder; that can only make fewer orders risky, never more.
     */
    static boolean riskyOrder(List<String> order) throws Exception {
        assertTrue("AC2 SalesBatchProcessTest, the class that reads a report, is not among " + order,
                order.contains(SalesBatchProcessTest.class.getName()));
        for (String name : order) {
            Class<?> c = Class.forName(name, false, RunOrderAcceptanceTest.class.getClassLoader());
            if (SET_NO_REPORT_FOLDER.contains(c.getSimpleName())) {
                return true;
            }
            org.junit.runner.RunWith runWith = c.getAnnotation(org.junit.runner.RunWith.class);
            boolean spring = runWith != null
                    && org.springframework.test.context.junit4.SpringJUnit4ClassRunner.class.isAssignableFrom(runWith.value());
            if (spring || c.getSimpleName().equals(SETS_REPORT_FOLDER_WITHOUT_SPRING_RUNNER)) {
                return false;
            }
        }
        return false;
    }

    @Test
    public void AC3_pipelineRunsTheJavaTestsInNoFixedOrder() throws Exception {
        String ci = new String(Files.readAllBytes(repoRoot().resolve(".github/workflows/ci.yml")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("mvn [^\\n]*\\btest\\b[^\\n]*").matcher(ci);
        assertTrue("AC3 no 'mvn ... test' line in ci.yml", m.find());
        String line = m.group();
        assertTrue("AC3 the Java test step does not run in random order: " + line,
                line.contains("-Dsurefire.runOrder=random"));
        Matcher orders = Pattern.compile("surefire\\.runOrder=(\\w+)").matcher(ci);
        while (orders.find()) {
            assertEquals("AC3 a fixed run order is still set in ci.yml", "random", orders.group(1));
        }
    }

    @Test
    public void AC4_existingTestStepsAndAssertionsAreUnchanged() throws Exception {
        Map<String, String> recorded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get(RunOrderAcceptanceTest.class
                .getResource("/acceptance/SBM-6-existing-test-methods.txt").toURI()), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            recorded.put(parts[0], parts[1]);
        }
        assertFalse("AC4 the recorded list of existing test methods is empty", recorded.isEmpty());
        Map<String, String> now = Fingerprints.of(repoRoot());
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> e : recorded.entrySet()) {
            String current = now.get(e.getKey());
            if (current == null) {
                differences.add("missing: " + e.getKey());
            } else if (!current.equals(e.getValue())) {
                differences.add("changed: " + e.getKey());
            }
        }
        assertTrue("AC4 existing test methods differ from main at 5a91d26: " + differences, differences.isEmpty());
    }

    // ---- the child JVM ----

    /** Every workflow-app test class with a @Test method, but this one, sorted by name. */
    static List<String> testClasses() throws Exception {
        Path root = Paths.get(RunOrderAcceptanceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> names;
        try (Stream<Path> files = Files.walk(root)) {
            names = files.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                    .map(p -> root.relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                    .collect(Collectors.toList());
        }
        List<String> tests = new ArrayList<>();
        for (String name : names) {
            if (name.equals(RunOrderAcceptanceTest.class.getName())) {
                continue;
            }
            Class<?> c = Class.forName(name, false, RunOrderAcceptanceTest.class.getClassLoader());
            for (Method m : c.getMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    tests.add(name);
                    break;
                }
            }
        }
        Collections.sort(tests);
        assertTrue("found no test classes in " + root, tests.size() > 3);
        return tests;
    }

    static void runInChildJvm(String label, List<String> classes) throws Exception {
        // Under target/, so the child's log is kept with the build's other output.
        Path tmp = Files.createTempDirectory(Files.createDirectories(Paths.get("target", "sbm6-run-order")),
                label.replaceAll("\\W+", "-") + "-");
        Path freshTmp = Files.createDirectories(tmp.resolve("tmpdir"));
        Path jar = classpathJar(tmp.resolve("classpath.jar"));
        Path log = tmp.resolve("child.log");
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Djava.io.tmpdir=" + freshTmp);
        cmd.add("-cp");
        cmd.add(jar.toString());
        cmd.add(Child.class.getName());
        cmd.addAll(classes);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            fail(label + ": the child JVM did not finish in 10 minutes; log " + log);
        }
        if (p.exitValue() != 0) {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> summary = lines.stream().filter(l -> l.startsWith("SBM-6 ")).collect(Collectors.toList());
            fail(label + " failed. Order: " + classes + "\n" + String.join("\n", summary) + "\nFull log: " + log);
        }
    }

    /** A jar whose manifest carries the test classpath: Windows caps a command line and Java 8 has no @argfiles. */
    static Path classpathJar(Path jar) throws IOException {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        StringBuilder urls = new StringBuilder();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            urls.append(new File(entry).toURI().toURL().toExternalForm()).append(' ');
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, urls.toString().trim());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            // the manifest is all it needs
        }
        return jar;
    }

    /** Runs the named classes in the given order; a failed test method is retried up to RETRIES times. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            JUnitCore junit = new JUnitCore();
            int failed = 0;
            for (String name : args) {
                Class<?> c = Class.forName(name);
                Result result = junit.run(c);
                System.out.println("SBM-6 ran " + name + ": " + result.getRunCount() + " run, "
                        + result.getFailureCount() + " failed");
                for (Failure f : result.getFailures()) {
                    String method = f.getDescription().getMethodName();
                    boolean passed = false;
                    for (int i = 1; i <= RETRIES && method != null && !passed; i++) {
                        passed = junit.run(Request.method(c, method)).wasSuccessful();
                        System.out.println("SBM-6 retry " + i + " of " + name + "." + method + ": "
                                + (passed ? "passed" : "failed"));
                    }
                    if (!passed) {
                        failed++;
                        System.out.println("SBM-6 FAILED " + name + "." + method + ": " + f.getMessage());
                        System.out.println(f.getTrace());
                    }
                }
            }
            System.out.println("SBM-6 done, " + failed + " test(s) failed after retries");
            System.exit(failed == 0 ? 0 : 1);
        }
    }

    // ---- AC4 fingerprints ----

    static Path repoRoot() throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".github/workflows/ci.yml"))) {
            dir = dir.getParent();
        }
        assertTrue("repository root not found above " + Paths.get("").toAbsolutePath(), dir != null);
        return dir;
    }

    /**
     * A SHA-256 per @Test method in every module's src/test/java and src/test/scala, from the annotation to the method's end,
     * with comments and whitespace removed (so line endings and formatting do not count; steps and assertions do).
     * Run main with the repository root and an output file to record the list.
     */
    public static final class Fingerprints {

        static final List<String> MODULES = java.util.Arrays.asList("common", "spark-job", "workflow-app");

        public static void main(String[] args) throws Exception {
            StringBuilder out = new StringBuilder("# SBM-6 AC4: existing @Test methods at main 5a91d26 (path#method sha256)\n");
            for (Map.Entry<String, String> e : of(Paths.get(args[0])).entrySet()) {
                out.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            }
            Files.write(Paths.get(args[1]), out.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Map<String, String> of(Path repo) throws Exception {
            Map<String, String> result = new LinkedHashMap<>();
            for (String module : MODULES) {
              for (String language : java.util.Arrays.asList("java", "scala")) {
                Path src = repo.resolve(module).resolve("src/test/" + language);
                if (!Files.isDirectory(src)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(src)) {
                    files = walk.filter(p -> p.toString().endsWith("." + language)).sorted().collect(Collectors.toList());
                }
                for (Path file : files) {
                    String rel = repo.relativize(file).toString().replace(File.separatorChar, '/');
                    if (rel.endsWith("/RunOrderAcceptanceTest.java")) {
                        continue;
                    }
                    String code = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    for (Map.Entry<String, String> m : testMethods(code).entrySet()) {
                        result.put(rel + "#" + m.getKey(), sha256(m.getValue().replaceAll("\\s+", "")));
                    }
                }
              }
            }
            return result;
        }

        static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\s*\\([^)]*\\))?");

        /**
         * Method name to source text, for each method annotated @Test (Java or Scala, with or without arguments). A
         * method runs from its first annotation line to the line before the next line indented no deeper than that
         * annotation which is not a closing bracket (the next member or the end of the class).
         */
        static Map<String, String> testMethods(String code) {
            Map<String, String> methods = new LinkedHashMap<>();
            String[] lines = code.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (!trimmed.matches("@Test\\b(?!\\w).*")) {
                    continue;
                }
                int indent = indentOf(lines[i]);
                StringBuilder text = new StringBuilder(lines[i]).append('\n');
                String signature = ANNOTATION.matcher(trimmed).replaceAll("").trim();
                int j = i + 1;
                while (signature.isEmpty() && j < lines.length) {
                    text.append(lines[j]).append('\n');
                    signature = ANNOTATION.matcher(lines[j].trim()).replaceAll("").trim();
                    j++;
                }
                for (; j < lines.length; j++) {
                    String t = lines[j].trim();
                    if (!t.isEmpty() && indentOf(lines[j]) <= indent && !t.startsWith("}") && !t.startsWith(")")) {
                        break;
                    }
                    if (!t.isEmpty() && indentOf(lines[j]) < indent) {
                        break;
                    }
                    text.append(lines[j]).append('\n');
                }
                Matcher name = Pattern.compile("(\\w+)\\s*\\(").matcher(signature);
                if (name.find()) {
                    methods.put(name.group(1), text.toString());
                }
                i = j - 1;
            }
            return methods;
        }

        static int indentOf(String line) {
            int n = 0;
            while (n < line.length() && Character.isWhitespace(line.charAt(n))) {
                n++;
            }
            return n;
        }

        static int endOfLiteral(String s, int start) {
            char quote = s.charAt(start);
            for (int i = start + 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    return i;
                }
            }
            return s.length() - 1;
        }

        static String stripComments(String s) {
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    int end = endOfLiteral(s, i);
                    out.append(s, i, end + 1);
                    i = end;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? s.length() : end + 1;
                    out.append(' ');
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        static String sha256(String text) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }
}
