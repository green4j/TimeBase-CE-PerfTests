package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {

    @Test
    void plainTextAlignsTablesAndDropsMarkup() {
        final String text = Report.plain(java.util.List.of("# Title", "", "## Setup", "- Profile: `latency`",
                "| Scenario | orig | f2 |", "|---|---|---|", "| **rate=1000** | 1.5 | 12.25 |", "| a\\|b | 100.0 | 2 |", "after"));

        final String hashes = "#".repeat(110);
        final String rule = "=".repeat(110);
        assertTrue(text.startsWith(hashes + "\n# Title\n" + hashes + "\n\n\n" + rule + "\nSETUP\n" + rule + "\n\n- Profile: latency\n"), text);
        assertTrue(text.contains("- Profile: latency\n\nScenario    orig     f2\n.........  .....  .....\nrate=1000    1.5  12.25\na|b        100.0      2\n\nafter\n"), text);
    }

    @Test
    void setupSaysWhatWasMeasuredAndOnWhat() throws Exception {
        final JsonNode campaign = Output.JSON.readTree("{\"seed\": 7, \"benchFile\": \"/b/bench.json\","
                + " \"profile\": {\"name\": \"latency\", \"depth\": \"medium\", \"loads\": [\"light\"], \"topology\": \"one2three\","
                + " \"overrides\": [\"symbols=1000\"]},"
                + " \"servers\": [{\"id\": \"orig\", \"git\": {\"repo\": \"/tb\", \"ref\": \"main\"},"
                + " \"built\": {\"commit\": \"6707be73ee8c21290772e17432417e0bb5c95352\", \"uncommittedChanges\": false},"
                + " \"configurations\": {\"g1\": {\"port\": 18011, \"jvmArgs\": [\"-Xmx4g\", \"-XX:+UseG1GC\"]}}},"
                + " {\"id\": \"f2\", \"dist\": \"/d\", \"distSha256\": \"abcdef0123456789\", \"configurations\": {\"g1\": {\"port\": 18011}}}],"
                + " \"clients\": {\"default\": {\"jvmArgs\": [\"-Xmx2g\"]}}}");
        final JsonNode env = Output.JSON.readTree("{\"osName\": \"Linux\", \"osVersion\": \"6.1\", \"osArch\": \"amd64\","
                + " \"processors\": 16, \"runnerJava\": \"Corretto 21\", \"suiteCommit\": \"287cd10e94057f7e\", \"suiteUncommittedChanges\": true}");
        final StringWriter out = new StringWriter();
        try (PrintWriter md = new PrintWriter(out)) {
            Report.setup(md, campaign, env, "f2", 10, 9, "12 min 5 s in runs", java.nio.file.Path.of("results/x"));
        }
        final String s = out.toString();

        for (String expected : new String[] {
                "| Profile | `latency` |", "| Depth | medium |", "| Loads | light |", "| Topology | one2three |",
                "| Overrides | `--set=symbols=1000` |", "## Bench", "| Bench file | `/b/bench.json` |", "## Campaign", "| Results | `results/x` |", "| Seed | 7 |", "| Runs | 10 planned, 9 ok |",
                "| Time | 12 min 5 s in runs |", "| Machine | Linux 6.1 amd64, 16 CPUs |", "| Runner | Java Corretto 21 |",
                "| Suite | 287cd10e9405 with uncommitted changes |",
                "### Baseline and candidates", "| orig | candidate | git /tb @ main = 6707be73ee8c | **g1**: port 18011, jvmArgs -Xmx4g -XX:+UseG1GC |",
                "| f2 | baseline | dist /d, sha256 abcdef012345 | **g1**: port 18011 |",
                "### Client", "| writer and readers | **default**: jvmArgs -Xmx2g |"})
            assertTrue(s.contains(expected), "The setup misses: " + expected + "\n" + s);
    }

    private static String run(String suite, String type, String server, String status, String metrics) {
        return "{\"id\": \"" + suite + "_r01_" + server + "\", \"suite\": \"" + suite + "\", \"type\": \"" + type + "\","
                + " \"server\": \"" + server + "\", \"repetition\": 1, \"params\": {\"mode\": \"LOW_LATENCY\"}, \"status\": \""
                + status + "\", \"durationMillis\": 4000, \"startedAt\": \"2026-11-25T10:00:00Z\", \"metrics\": " + metrics + "}";
    }

    /** A campaign of orig and f2 where only f2 measured throughput and no latency run is ok. */
    private static Path results(Path dir) throws Exception {
        Files.writeString(dir.resolve("campaign.json"), "{\"campaign\": \"t\", \"seed\": 1, \"baseline\": \"orig\","
                + " \"profile\": {\"name\": \"t\", \"depth\": \"fast\"}, \"clients\": {\"default\": {}},"
                + " \"servers\": [{\"id\": \"orig\", \"url\": \"u\"}, {\"id\": \"f2\", \"url\": \"u\"}],"
                + " \"suites\": [{\"name\": \"throughput\", \"repetitions\": 1}, {\"name\": \"latency\", \"repetitions\": 1}]}");
        final String[] runs = {
                run("throughput", "throughput", "orig", "failed", "{}"),
                run("throughput", "throughput", "f2", "ok", "{\"writer\": {\"rate\": 500000}}"),
                run("latency", "latency", "orig", "invalid", "{}"),
                run("latency", "latency", "f2", "failed", "{}")};
        Files.createDirectories(dir.resolve("runs"));
        final StringBuilder plan = new StringBuilder("[");
        for (String r : runs) {
            final JsonNode node = Output.JSON.readTree(r);
            Files.writeString(dir.resolve("runs").resolve(node.path("id").asText() + ".json"), r);
            plan.append(plan.length() > 1 ? ", " : "").append("{\"id\": \"").append(node.path("id").asText()).append("\"}");
        }
        Files.writeString(dir.resolve("expanded-plan.json"), plan.append("]").toString());
        return dir;
    }

    @Test
    void candidateWithoutBaselineStaysInTheSummary(@TempDir Path dir) throws Exception {
        Report.run(Args.parse(new String[] {"--results=" + results(dir)}));

        final String csv = Files.readString(dir.resolve("summary.csv"));
        assertTrue(csv.contains("throughput,\"all\",writer.rate,f2,1,500.0000,"), "The candidate's statistics were dropped:\n" + csv);
    }

    @Test
    void suiteWithoutOkRunsStillGetsItsTime(@TempDir Path dir) throws Exception {
        Report.run(Args.parse(new String[] {"--results=" + results(dir)}));

        final String md = Files.readString(dir.resolve("report.md"));
        assertTrue(md.contains("| latency | all | 2 | 8 s | 4.0 | 4.0 |"), "A suite whose runs all failed has no time:\n" + md);
        assertTrue(md.contains("| all | | 4 | 16 s |"), "The total misses the failed runs:\n" + md);
    }

    @Test
    void metricWithoutValuesHasNoTable(@TempDir Path dir) throws Exception {
        Report.run(Args.parse(new String[] {"--results=" + results(dir)}));

        final String md = Files.readString(dir.resolve("report.md"));
        assertTrue(md.contains("### Writer rate, K msg/s"), "A metric with values lost its table:\n" + md);
        assertFalse(md.contains("### Writer CPU"), "A metric no run has got an empty table:\n" + md);
    }

    /** A latency run with {@code n} messages of 1 ms and {@code slow} of them of 10 ms instead. */
    private static String latencyRun(String server, int repetition, int n, int slow) {
        final org.HdrHistogram.Histogram h = new org.HdrHistogram.Histogram(3);
        h.recordValueWithCount(1_000_000, n - slow);
        if (slow > 0)
            h.recordValueWithCount(10_000_000, slow);
        return "{\"id\": \"latency_r0" + repetition + "_" + server + "\", \"suite\": \"latency\", \"type\": \"latency\","
                + " \"server\": \"" + server + "\", \"repetition\": " + repetition + ", \"params\": {}, \"status\": \"ok\","
                + " \"durationMillis\": 4000, \"metrics\": {\"transport\": " + Output.latency(h) + "}}";
    }

    /** Writes a campaign of orig against f2 with one suite and these runs, and makes its report. */
    private static String report(Path dir, String suite, int repetitions, List<String> runs) throws Exception {
        Files.writeString(dir.resolve("campaign.json"), "{\"campaign\": \"t\", \"seed\": 1, \"baseline\": \"orig\","
                + " \"profile\": {\"name\": \"t\", \"depth\": \"fast\"}, \"clients\": {\"default\": {}},"
                + " \"servers\": [{\"id\": \"orig\", \"url\": \"u\"}, {\"id\": \"f2\", \"url\": \"u\"}],"
                + " \"suites\": [{\"name\": \"" + suite + "\", \"type\": \"" + suite + "\", \"repetitions\": " + repetitions + "}]}");
        Files.createDirectories(dir.resolve("runs"));
        final StringBuilder plan = new StringBuilder("[");
        for (String r : runs) {
            final String id = Output.JSON.readTree(r).path("id").asText();
            Files.writeString(dir.resolve("runs").resolve(id + ".json"), r);
            plan.append(plan.length() > 1 ? ", " : "").append("{\"id\": \"").append(id).append("\"}");
        }
        Files.writeString(dir.resolve("expanded-plan.json"), plan.append("]").toString());
        Report.run(Args.parse(new String[] {"--results=" + dir}));
        return Files.readString(dir.resolve("report.md"));
    }

    private static String throughputRun(String server, int repetition, double rate) {
        return "{\"id\": \"throughput_r0" + repetition + "_" + server + "\", \"suite\": \"throughput\", \"type\": \"throughput\","
                + " \"server\": \"" + server + "\", \"repetition\": " + repetition + ", \"params\": {}, \"status\": \"ok\","
                + " \"durationMillis\": 4000, \"metrics\": {\"writer\": {\"rate\": " + rate + "}}}";
    }

    private static String row(String md, String table) {
        final String rest = md.substring(md.indexOf("### " + table));
        return rest.substring(rest.indexOf("| all |"), rest.indexOf('\n', rest.indexOf("| all |")));
    }

    @Test
    void diffHasThePairedIntervalOfTheRatio(@TempDir Path dir) throws Exception {
        final double[] base = {500_000, 510_000, 490_000, 505_000, 495_000};
        final double[] faster = {550_000, 565_000, 535_000, 560_000, 540_000};
        final List<String> runs = new ArrayList<>();
        for (int r = 0; r < base.length; r++) {
            runs.add(throughputRun("orig", r + 1, base[r]));
            runs.add(throughputRun("f2", r + 1, faster[r]));
        }
        final String row = row(report(dir, "throughput", 5, runs), "Writer rate");

        assertTrue(row.contains("| +10.0 % [+8.9, +11.0] | 5/5 |"), "No t interval of the paired log ratio:\n" + row);
    }

    @Test
    void intervalHoldingNoDifferenceIsNoise(@TempDir Path dir) throws Exception {
        final double[] base = {500_000, 510_000, 490_000};
        final double[] same = {505_000, 500_000, 495_000};
        final List<String> runs = new ArrayList<>();
        for (int r = 0; r < base.length; r++) {
            runs.add(throughputRun("orig", r + 1, base[r]));
            runs.add(throughputRun("f2", r + 1, same[r]));
        }
        final String row = row(report(dir, "throughput", 3, runs), "Writer rate");

        assertTrue(row.matches(".*\\| [-+]\\d+\\.\\d % \\[-\\d+\\.\\d, \\+\\d+\\.\\d\\] noise \\| \\d/3 \\|$"),
                "An interval across 0 is not marked as noise:\n" + row);
    }

    @Test
    void pooledLatencyGetsABootstrapIntervalFromEnoughPairs(@TempDir Path dir) throws Exception {
        final List<String> runs = new ArrayList<>();
        for (int r = 1; r <= Report.MIN_BOOTSTRAP_PAIRS; r++) {
            runs.add(latencyRun("orig", r, 100, 10));
            runs.add(latencyRun("f2", r, 100, 0));
        }
        final String md = report(dir, "latency", Report.MIN_BOOTSTRAP_PAIRS, runs);

        assertTrue(row(md, "p99, ms").contains("| -90.0 % [-90.0, -90.0] |"), "No bootstrap interval:\n" + row(md, "p99, ms"));
        assertTrue(row(md, "p50, ms").contains("| +0.0 % [+0.0, +0.0] noise |"), "No bootstrap interval:\n" + row(md, "p50, ms"));
    }

    @Test
    void percentilesComeFromTheHistogramsOfAllRuns(@TempDir Path dir) throws Exception {
        final String md = report(dir, "latency", 2, List.of(latencyRun("orig", 1, 100, 0), latencyRun("orig", 2, 100, 2),
                latencyRun("f2", 1, 100, 0), latencyRun("f2", 2, 100, 0)));

        final String p99 = md.substring(md.indexOf("### p99, ms"));
        assertTrue(p99.contains("| all | 1.00, runs 5.50 ± 6.37 (116 %) | 1.00, runs 1.00 ± 0.00 (0 %) | +0.0 % | 1/2 |"),
                "p99 is not that of the pooled histograms, 2 slow messages of 200:\n" + p99);
        final String max = md.substring(md.indexOf("### max, ms"));
        assertTrue(max.contains("| all | 10.00, runs 5.50 ± 6.37 (116 %) |"), "max is not the largest of all runs:\n" + max);
        final String csv = Files.readString(dir.resolve("summary.csv"));
        assertTrue(csv.contains("latency,\"all\",transport.p99,orig,2,5.5"), "summary.csv lost the runs' mean:\n" + csv);
        assertTrue(csv.lines().anyMatch(l -> l.startsWith("latency,\"all\",transport.p99,orig,") && l.endsWith(",1.0004,,")),
                "summary.csv has no pooled value:\n" + csv);
    }

    @Test
    void unknownBaselineIsAnError(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Report.run(Args.parse(new String[] {"--results=" + results(dir), "--baseline=nope"})));
    }

    private static JsonNode latency(double transportP99, double scheduleP99, double transportMax, double scheduleMax) throws Exception {
        return Output.JSON.readTree(String.format(java.util.Locale.ROOT, "{\"metrics\": {\"transport\": {\"p99\": %f, \"max\": %f},"
                + " \"schedule\": {\"p99\": %f, \"max\": %f}}}", transportP99, transportMax, scheduleP99, scheduleMax));
    }

    @Test
    void scheduleSaysWhetherTheWriterKeptUp() throws Exception {
        assertEquals("on time", Report.schedule(java.util.List.of(latency(2000, 2100, 9000, 9000))),
                "Latency from schedule within 10 % was reported as late");
        assertEquals("late to send: p99 +2.00 ms", Report.schedule(java.util.List.of(latency(2000, 4000, 9000, 9000))));
        assertEquals("late to send: p99 +2.00 ms, max +4.00 ms",
                Report.schedule(java.util.List.of(latency(2000, 4000, 9000, 11000), latency(2000, 4000, 9000, 15000))),
                "The means over repetitions were not compared");
        assertEquals("", Report.schedule(java.util.List.of()), "A server without runs got a verdict");
        final JsonNode withoutSchedule = Output.JSON.readTree("{\"metrics\": {\"transport\": {\"p99\": 9000, \"max\": 9000}}}");
        assertEquals("on time", Report.schedule(java.util.List.of(latency(2000, 2000, 9000, 9000), withoutSchedule)),
                "A run without latency from schedule was compared with the others' transport");
    }
}
