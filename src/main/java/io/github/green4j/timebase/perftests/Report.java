package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregates the run files of a campaign into {@code report.md} and {@code summary.csv}: per suite, scenario and
 * metric, mean ± std dev over repetitions of the baseline and of every candidate with its difference from the
 * baseline, after a setup section that says what was measured and on what.
 */
final class Report {

    private static final class Metric {
        final String path;
        final String label;
        final double scale;
        final boolean higherIsBetter;

        Metric(String path, String label, double scale, boolean higherIsBetter) {
            this.path = path;
            this.label = label;
            this.scale = scale;
            this.higherIsBetter = higherIsBetter;
        }
    }

    private static final Map<String, List<Metric>> METRICS = Map.of(
            "throughput", List.of(
                    new Metric("writer.rate", "Writer rate, K msg/s", 1e-3, true),
                    new Metric("writer.cpuNsPerMsg", "Writer CPU, ns/msg", 1, false)),
            "latency", List.of(
                    new Metric("transport.p50", "p50, ms", 1e-3, false),
                    new Metric("transport.p99", "p99, ms", 1e-3, false),
                    new Metric("transport.p99_9", "p99.9, ms", 1e-3, false),
                    new Metric("transport.p99_99", "p99.99, ms", 1e-3, false),
                    new Metric("transport.max", "max, ms", 1e-3, false),
                    new Metric("schedule.p99", "p99 from schedule, ms", 1e-3, false),
                    new Metric("schedule.max", "max from schedule, ms", 1e-3, false),
                    new Metric("send.p99", "send() p99, µs", 1, false),
                    new Metric("send.max", "send() max, µs", 1, false)),
            "correctness", List.of(
                    new Metric("pass", "Pass (1 = all readers correct)", 1, true)));

    /** The runs of one suite and the parameters its scenarios differ in. */
    private static final class Suite {
        final String name;
        final List<JsonNode> runs;
        final Set<String> axes;

        Suite(String name, List<JsonNode> runs, Set<String> axes) {
            this.name = name;
            this.runs = runs;
            this.axes = axes;
        }
    }

    private Report() {
    }

    /** What was measured, on what and in which launch: the profile, the bench and the campaign. */
    static void setup(PrintWriter md, JsonNode campaign, JsonNode env, String baseline, int planned, long ok, String time, Path results) {
        md.printf("## Profile%n%n| Setting | Value |%n|---|---|%n");
        final JsonNode p = campaign.path("profile");
        row(md, "Profile", "`" + p.path("name").asText() + "`" + (p.hasNonNull("file") ? " " + p.get("file").asText() : ""));
        row(md, "Depth", p.path("depth").asText());
        if (p.path("loads").size() > 0)
            row(md, "Loads", join(p.path("loads"), ", "));
        if (p.hasNonNull("topology"))
            row(md, "Topology", p.get("topology").asText());
        if (p.path("overrides").size() > 0) {
            final List<String> sets = new ArrayList<>();
            p.path("overrides").forEach(o -> sets.add("`--set=" + o.asText() + "`"));
            row(md, "Overrides", String.join(" ", sets));
        }
        md.println();

        md.printf("## Bench%n%n| Setting | Value |%n|---|---|%n");
        row(md, "Bench file", campaign.hasNonNull("benchFile") ? "`" + campaign.get("benchFile").asText() + "`" : "none, the command line");
        md.printf("%n### Baseline and candidates%n%n| Id | Role | git / dist / image / url | Configurations |%n|---|---|---|---|%n");
        for (JsonNode s : campaign.path("servers")) {
            final String id = s.path("id").asText();
            md.printf("| %s | %s | %s | %s |%n", id, id.equals(baseline) ? "baseline" : "candidate", code(s),
                    configurations(s.path("configurations")));
        }
        md.printf("%n### Client%n%n| Client | Configurations |%n|---|---|%n| writer and readers | %s |%n%n", configurations(campaign.path("clients")));

        md.printf("## Campaign%n%n| Setting | Value |%n|---|---|%n");
        row(md, "Name", campaign.path("campaign").asText());
        row(md, "Results", "`" + results + "`");
        row(md, "Runs", planned + " planned, " + ok + " ok");
        if (time != null)
            row(md, "Time", time);
        row(md, "Seed", campaign.path("seed").asText());
        if (env != null) {
            row(md, "Machine", env.path("osName").asText() + " " + env.path("osVersion").asText() + " " + env.path("osArch").asText()
                    + ", " + env.path("processors").asInt() + " CPUs");
            row(md, "Runner", "Java " + env.path("runnerJava").asText());
            row(md, "Suite", shortHash(env.path("suiteCommit").asText("unknown"))
                    + (env.path("suiteUncommittedChanges").asBoolean(false) ? " with uncommitted changes" : ""));
        }
        md.println();
    }

    private static void row(PrintWriter md, String setting, String value) {
        md.printf("| %s | %s |%n", setting, value.replace("|", "\\|"));
    }

    static void howToRead(PrintWriter md, String baseline, boolean compared, Set<String> types) {
        md.printf("## How to read%n%n");
        md.printf("- Only planned runs with status `ok` are aggregated.%n");
        md.printf("- A cell is the mean ± std dev over repetitions and the coefficient of variation; `n=… of …` when fewer runs "
                + "than planned are ok.%n");
        if (types.contains("latency"))
            md.printf("- Percentiles and max come first from the histograms of all the runs merged, as JMH pools samples, then "
                    + "`runs` gives the mean ± std dev of the runs' own values. Runs recorded without histograms get the mean only.%n");
        if (types.contains("latency")) {
            md.printf("- Latency, lower is better: p50 … max are transport, `receive − sent` from just before the writer's "
                    + "`send()`, the client library included; from schedule is `receive − scheduled` and adds the time a message "
                    + "waited to be sent; send() is how long the writer's `send()` took, in µs.%n");
            md.printf("- A long transport with a short send(): the delay is after the client, in the server or in the delivery. "
                    + "A long send(): the delay is in the send path — serialising, the loader's buffer, the writer's thread, "
                    + "or a server that holds the writer back.%n");
            md.printf("- p99.9, p99.99 and max come from a handful of messages per run: compare them over many repetitions (≥ 20).%n");
        }
        if (types.contains("throughput"))
            md.printf("- Throughput: writer rate in K msg/s, higher is better; writer CPU in ns per message, lower is better.%n");
        if (types.contains("correctness"))
            md.printf("- Pass: 1 when every reader received every message exactly once, in order.%n");
        if (compared) {
            md.printf("- Diff: a candidate's pooled value, or mean, against that of the baseline `%s`, in percent.%n", baseline);
            md.printf("- Wins `k/n`: the candidate was better than the baseline in `k` of `n` repetitions paired by repetition. "
                    + "A large Diff with `k` near `n/2` is not a consistent difference and may come from outliers.%n");
        }
        md.printf("- `summary.csv` adds median, quartiles, min and max.%n%n");
    }

    /** The run time of the runs and the wall time from the first start to the last end, or {@code null} without runs. */
    static String totalTime(List<JsonNode> runs) {
        long sum = 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (JsonNode r : runs) {
            final long duration = r.path("durationMillis").asLong();
            sum += duration;
            if (r.hasNonNull("startedAt")) {
                final long start = java.time.Instant.parse(r.get("startedAt").asText()).toEpochMilli();
                first = Math.min(first, start);
                last = Math.max(last, start + duration);
            }
        }
        if (runs.isEmpty())
            return null;
        return duration(sum) + " in runs" + (first <= last ? ", " + duration(last - first) + " from the first start to the last end" : "");
    }

    static String duration(long millis) {
        final long seconds = Math.round(millis / 1000.0);
        if (seconds < 60)
            return seconds + " s";
        if (seconds < 3600)
            return seconds / 60 + " min " + seconds % 60 + " s";
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    /** How long the runs of every scenario took, those not ok included: in all, and a run of each server on average. */
    private static void time(PrintWriter md, List<Suite> suites, String baseline, List<String> candidates) {
        if (suites.isEmpty())
            return;
        final List<String> servers = new ArrayList<>();
        servers.add(baseline);
        servers.addAll(candidates);
        md.printf("## Time%n%n| Suite | Scenario | Runs | Total");
        servers.forEach(id -> md.printf(" | %s, s per run", id));
        md.printf(" |%n|---|---|---|---%s|%n", "|---".repeat(servers.size()));
        long all = 0;
        int count = 0;
        for (Suite suite : suites) {
            for (Map.Entry<String, Map<String, List<JsonNode>>> scenario : byScenario(suite.runs, suite.axes).entrySet()) {
                long total = 0;
                int n = 0;
                final StringBuilder perServer = new StringBuilder();
                for (String id : servers) {
                    final List<JsonNode> own = scenario.getValue().getOrDefault(id, List.of());
                    final long sum = own.stream().mapToLong(r -> r.path("durationMillis").asLong()).sum();
                    total += sum;
                    n += own.size();
                    perServer.append(" | ").append(own.isEmpty() ? "" : String.format(Locale.ROOT, "%.1f", sum / 1000.0 / own.size()));
                }
                all += total;
                count += n;
                md.printf("| %s | %s | %d | %s%s |%n", suite.name, scenario.getKey(), n, duration(total), perServer);
            }
        }
        md.printf("| all | | %d | %s%s |%n%n", count, duration(all), " | ".repeat(servers.size()));
    }

    private static String code(JsonNode s) {
        if (s.has("git")) {
            final JsonNode built = s.path("built");
            final StringBuilder sb = new StringBuilder("git " + s.path("git").path("repo").asText() + " @ " + s.path("git").path("ref").asText());
            if (built.hasNonNull("commit") && !built.get("commit").asText().startsWith(s.path("git").path("ref").asText()))
                sb.append(" = ").append(shortHash(built.get("commit").asText()));
            if (built.path("uncommittedChanges").asBoolean(false))
                sb.append(" with uncommitted changes ").append(shortHash(built.path("changesSha256").asText()));
            return overlay(s, sb.toString());
        }
        if (s.hasNonNull("dist"))
            return overlay(s, "dist " + s.get("dist").asText() + (s.hasNonNull("distSha256") ? ", sha256 " + shortHash(s.get("distSha256").asText()) : ""));
        if (s.hasNonNull("image"))
            return "image " + s.get("image").asText() + (s.hasNonNull("imageId") ? " = " + shortHash(s.get("imageId").asText().replaceFirst("^sha256:", "")) : "");
        return "url " + s.path("url").asText();
    }

    private static String overlay(JsonNode s, String code) {
        return s.hasNonNull("overlay") ? code + " + overlay " + s.get("overlay").asText()
                + (s.hasNonNull("overlaySha256") ? ", sha256 " + shortHash(s.get("overlaySha256").asText()) : "") : code;
    }

    /** {@code id: key value, ...; id: ...}, the defaults included, as they were applied. */
    private static String configurations(JsonNode byId) {
        final List<String> all = new ArrayList<>();
        byId.fields().forEachRemaining(e -> {
            final List<String> settings = new ArrayList<>();
            e.getValue().fields().forEachRemaining(k -> settings.add(k.getKey() + " "
                    + (k.getValue().isArray() ? join(k.getValue(), " ") : k.getValue().asText())));
            all.add("**" + e.getKey() + "**: " + (settings.isEmpty() ? "defaults" : String.join(", ", settings)));
        });
        return String.join("; ", all).replace("|", "\\|");
    }

    private static String join(JsonNode array, String separator) {
        final List<String> items = new ArrayList<>();
        array.forEach(a -> items.add(a.asText()));
        return String.join(separator, items);
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    static void run(Args args) throws IOException {
        final Path dir = Path.of(args.string("results"));
        final JsonNode campaign = Output.JSON.readTree(dir.resolve("campaign.json").toFile());
        final Set<String> planned = new LinkedHashSet<>();
        Output.JSON.readTree(dir.resolve("expanded-plan.json").toFile()).forEach(r -> planned.add(r.path("id").asText()));
        final List<JsonNode> runs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir.resolve("runs"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList())) {
                final JsonNode run = Output.JSON.readTree(f.toFile());
                if (planned.contains(run.path("id").asText()))
                    runs.add(run);
            }
        }

        final Set<String> missing = new LinkedHashSet<>(planned);
        runs.forEach(r -> missing.remove(r.path("id").asText()));

        final List<String> servers = new ArrayList<>();
        campaign.path("servers").forEach(s -> servers.add(s.path("id").asText()));
        final String baseline = args.string("baseline", campaign.path("baseline").asText(servers.get(0)));
        if (!servers.contains(baseline))
            throw new IllegalArgumentException("baseline must be one of " + servers + ", got " + baseline);
        final List<String> candidates = servers.stream().filter(s -> !s.equals(baseline)).collect(Collectors.toList());

        try (PrintWriter md = new PrintWriter(Files.newBufferedWriter(dir.resolve("report.md"), StandardCharsets.UTF_8));
             PrintWriter csv = new PrintWriter(Files.newBufferedWriter(dir.resolve("summary.csv"), StandardCharsets.UTF_8))) {
            md.printf("# TimeBase CE performance: %s, %s%n%n", campaign.path("profile").path("name").asText(),
                    candidates.isEmpty() ? baseline : baseline + " vs " + String.join(", ", candidates));
            final Path environment = dir.resolve("environment.json");
            setup(md, campaign, Files.exists(environment) ? Output.JSON.readTree(environment.toFile()) : null, baseline,
                    planned.size(), runs.stream().filter(r -> "ok".equals(r.path("status").asText())).count(), totalTime(runs), dir);
            final Set<String> types = new LinkedHashSet<>();
            campaign.path("suites").forEach(s -> types.add(s.path("type").asText()));
            howToRead(md, baseline, !candidates.isEmpty(), types);
            csv.println("suite,scenario,metric,server,n,mean,std,cvPercent,median,q1,q3,min,max,diffPercent,wins,pairs,pooled");
            if (!missing.isEmpty()) {
                md.printf("**Incomplete campaign:** %d of %d planned runs have no result yet.%n%n", missing.size(), planned.size());
                for (String id : missing)
                    md.printf("- `%s`%n", id);
                md.println();
            }

            final List<Suite> suites = new ArrayList<>();
            final Map<String, List<JsonNode>> bySuite = new LinkedHashMap<>();
            for (JsonNode run : runs)
                bySuite.computeIfAbsent(run.path("suite").asText(), k -> new ArrayList<>()).add(run);

            for (Map.Entry<String, List<JsonNode>> suite : bySuite.entrySet()) {
                final List<JsonNode> ok = suite.getValue().stream()
                        .filter(r -> "ok".equals(r.path("status").asText())).collect(Collectors.toList());
                final String type = suite.getValue().get(0).path("type").asText();
                int suiteRepetitions = 1;
                for (JsonNode sd : campaign.path("suites")) {
                    if (suite.getKey().equals(sd.path("name").asText()))
                        suiteRepetitions = sd.path("repetitions").asInt(1);
                }
                final int repetitions = suiteRepetitions;
                suites.add(new Suite(suite.getKey(), suite.getValue(), varyingParams(suite.getValue())));
                md.printf("## %s%n%n", suite.getKey().equals(type) ? type : suite.getKey() + " (" + type + ")");
                failures(md, suite.getValue());
                if (ok.isEmpty())
                    continue;
                final Set<String> axes = varyingParams(ok);
                final Map<String, Map<String, List<JsonNode>>> byScenario = byScenario(ok, axes);

                for (Metric metric : METRICS.getOrDefault(type, List.of())) {
                    if (ok.stream().allMatch(r -> value(r, metric) == null))
                        continue;
                    md.printf("### %s%n%n", metric.label);
                    final StringBuilder header = new StringBuilder("| Scenario | " + baseline);
                    final StringBuilder rule = new StringBuilder("|---|---");
                    for (String c : candidates) {
                        header.append(" | ").append(c).append(" | Diff | Wins");
                        rule.append("|---|---|---");
                    }
                    md.println(header + " |");
                    md.println(rule + "|");
                    for (Map.Entry<String, Map<String, List<JsonNode>>> scenario : byScenario.entrySet()) {
                        final List<JsonNode> base = scenario.getValue().getOrDefault(baseline, List.of());
                        final Stats b = stats(base, metric);
                        final Double bp = pooled(base, metric);
                        final StringBuilder row = new StringBuilder("| " + scenario.getKey() + " | " + cell(b, bp, metric, repetitions));
                        if (b != null)
                            csv.println(csvLine(suite.getKey(), scenario.getKey(), metric, baseline, b, bp, null, -1, 0));
                        for (String c : candidates) {
                            final List<JsonNode> cand = scenario.getValue().getOrDefault(c, List.of());
                            final Stats s = stats(cand, metric);
                            final Double sp = pooled(cand, metric);
                            row.append(" | ").append(cell(s, sp, metric, repetitions));
                            if (b != null && s != null) {
                                final double bv = bp != null && sp != null ? bp : b.mean;
                                final double sv = bp != null && sp != null ? sp : s.mean;
                                final double diff = bv != 0 ? (sv / bv - 1) * 100 : Double.NaN;
                                final int[] wins = wins(base, cand, metric);
                                row.append(String.format(Locale.ROOT, " | %+.1f %% | %d/%d", diff, wins[0], wins[1]));
                                csv.println(csvLine(suite.getKey(), scenario.getKey(), metric, c, s, sp, diff, wins[0], wins[1]));
                            } else {
                                row.append(" | | ");
                                if (s != null)
                                    csv.println(csvLine(suite.getKey(), scenario.getKey(), metric, c, s, sp, null, -1, 0));
                            }
                        }
                        md.println(row + " |");
                    }
                    md.println();
                }
                if ("latency".equals(type))
                    schedule(md, byScenario, baseline, candidates);
            }
            time(md, suites, baseline, candidates);
        }
        System.out.println();
        System.out.print(plain(Files.readAllLines(dir.resolve("report.md"), StandardCharsets.UTF_8)));
        System.out.println("Wrote " + dir.resolve("report.md") + " and " + dir.resolve("summary.csv"));
    }

    private static final String ESCAPED_BAR = "\u0000";

    /** The Markdown of a report for a terminal: tables aligned in columns, headings underlined, no inline markup. */
    static String plain(List<String> markdown) {
        final StringBuilder out = new StringBuilder();
        final List<List<String>> table = new ArrayList<>();
        for (String line : markdown) {
            if (line.startsWith("|")) {
                final List<String> cells = new ArrayList<>();
                for (String cell : line.replace("\\|", ESCAPED_BAR).split("\\|", -1))
                    cells.add(inline(cell.replace(ESCAPED_BAR, "|")).trim());
                cells.remove(0);
                cells.remove(cells.size() - 1);
                if (!cells.stream().allMatch(c -> c.matches(":?-+:?")))
                    table.add(cells);
                continue;
            }
            if (!table.isEmpty()) {
                table(out, table);
                table.clear();
            }
            final int level = line.startsWith("#") ? line.indexOf(' ') : 0;
            if (level > 0 && line.substring(0, level).chars().allMatch(c -> c == '#')) {
                final String title = inline(line.substring(level + 1));
                while (out.length() > 0 && !out.toString().endsWith("\n\n"))
                    out.append('\n');
                if (level == 1) {
                    out.append("#".repeat(WIDTH)).append('\n').append("# ").append(title).append('\n')
                            .append("#".repeat(WIDTH)).append("\n\n");
                } else if (level == 2) {
                    out.append('\n').append("=".repeat(WIDTH)).append('\n').append(title.toUpperCase(Locale.ROOT)).append('\n')
                            .append("=".repeat(WIDTH)).append("\n\n");
                } else {
                    final String head = "---- " + title + " ";
                    out.append(head).append("-".repeat(Math.max(4, WIDTH - head.length()))).append("\n\n");
                }
            } else if (!line.isEmpty() || !out.toString().endsWith("\n\n")) {
                wrap(out, inline(line));
            }
        }
        if (!table.isEmpty())
            table(out, table);
        return out.toString();
    }

    private static void table(StringBuilder out, List<List<String>> rows) {
        if (out.length() > 0 && !out.toString().endsWith("\n\n"))
            out.append('\n');
        final int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        final int[] width = new int[columns];
        final boolean[] numeric = new boolean[columns];
        for (int c = 1; c < columns; c++)
            numeric[c] = true;
        for (int r = 0; r < rows.size(); r++) {
            final List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                width[c] = Math.max(width[c], row.get(c).length());
                if (r > 0 && !row.get(c).isEmpty() && !row.get(c).matches("[-+]?[0-9].*"))
                    numeric[c] = false;
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            final List<String> row = rows.get(r);
            final StringBuilder line = new StringBuilder();
            for (int c = 0; c < columns; c++) {
                final String cell = c < row.size() ? row.get(c) : "";
                if (c > 0)
                    line.append("  ");
                line.append(numeric[c] ? " ".repeat(width[c] - cell.length()) + cell : cell + " ".repeat(width[c] - cell.length()));
            }
            out.append(line.toString().replaceAll("\\s+$", "")).append('\n');
            if (r == 0) {
                final StringBuilder rule = new StringBuilder();
                for (int c = 0; c < columns; c++)
                    rule.append(c > 0 ? "  " : "").append(".".repeat(width[c]));
                out.append(rule).append('\n');
            }
        }
        out.append('\n');
    }

    private static final int WIDTH = 110;

    /** A long line broken at spaces, the continuation of a list item indented under its text. */
    private static void wrap(StringBuilder out, String line) {
        final String indent = line.startsWith("- ") ? "  " : "";
        String rest = line;
        while (rest.length() > WIDTH) {
            int at = rest.lastIndexOf(' ', WIDTH);
            if (at <= indent.length())
                break;
            out.append(rest, 0, at).append('\n');
            rest = indent + rest.substring(at + 1);
        }
        out.append(rest).append('\n');
    }

    private static String inline(String text) {
        return text.replace("**", "").replace("`", "");
    }

    private static void failures(PrintWriter md, List<JsonNode> runs) {
        final long shared = runs.stream().filter(r -> !r.path("isolated").asBoolean(true)).count();
        if (shared > 0)
            md.printf("Runs against a server the suite does not manage (not isolated from each other): %d%n%n", shared);
        final List<JsonNode> failed = runs.stream().filter(r -> !"ok".equals(r.path("status").asText())).collect(Collectors.toList());
        if (failed.isEmpty())
            return;
        md.printf("Runs not aggregated: %d%n%n", failed.size());
        for (JsonNode r : failed)
            md.printf("- `%s` (%s): %s%n", r.path("id").asText(), r.path("status").asText(), r.path("error").asText());
        md.println();
    }

    private static Set<String> varyingParams(List<JsonNode> runs) {
        final Map<String, Set<String>> values = new LinkedHashMap<>();
        for (JsonNode run : runs)
            run.path("params").fields().forEachRemaining(e -> values.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue().asText()));
        final Set<String> varying = new LinkedHashSet<>();
        values.forEach((k, v) -> {
            if (v.size() > 1)
                varying.add(k);
        });
        return varying;
    }

    /** Runs by scenario, in the order of {@link #compareScenarios}, then by server. */
    private static Map<String, Map<String, List<JsonNode>>> byScenario(List<JsonNode> runs, Set<String> axes) {
        final Map<String, Map<String, List<JsonNode>>> byScenario = new TreeMap<>(Report::compareScenarios);
        for (JsonNode run : runs)
            byScenario.computeIfAbsent(scenario(run, axes), k -> new TreeMap<>())
                    .computeIfAbsent(run.path("server").asText(), k -> new ArrayList<>()).add(run);
        return byScenario;
    }

    private static String scenario(JsonNode run, Set<String> axes) {
        if (axes.isEmpty())
            return "all";
        return axes.stream().map(a -> a + "=" + run.path("params").path(a).asText()).collect(Collectors.joining(" "));
    }

    /** Orders scenarios by their values, numerically where both values are numbers. */
    private static int compareScenarios(String a, String b) {
        final String[] x = a.split(" ");
        final String[] y = b.split(" ");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            final String vx = x[i].substring(x[i].indexOf('=') + 1);
            final String vy = y[i].substring(y[i].indexOf('=') + 1);
            int c;
            try {
                c = Double.compare(Double.parseDouble(vx), Double.parseDouble(vy));
            } catch (NumberFormatException e) {
                c = vx.compareTo(vy);
            }
            if (c != 0)
                return c;
        }
        return Integer.compare(x.length, y.length);
    }

    /** From schedule within this share above transport is the same latency: the writer started {@code send()} on time. */
    static final double ON_SCHEDULE = 0.10;

    /** Whether the writer kept to the schedule, per scenario and server: latency from schedule against transport. */
    private static void schedule(PrintWriter md, Map<String, Map<String, List<JsonNode>>> byScenario, String baseline,
                                 List<String> candidates) {
        md.printf("### Writer and schedule%n%n");
        md.printf("p99 and max from schedule against transport over the pooled histograms of the runs that have both, "
                + "means over runs recorded without histograms. On time within %.0f %%: "
                + "the writer started `send()` on schedule, the delay is in `send()` or after it. Late to send: messages waited "
                + "to be sent — a late wakeup, blocked earlier sends, a burst, or a server that does not keep up.%n%n",
                ON_SCHEDULE * 100);
        md.printf("| Scenario | %s%s |%n|---|---%s|%n", baseline, candidates.isEmpty() ? "" : " | " + String.join(" | ", candidates),
                "|---".repeat(candidates.size()));
        for (Map.Entry<String, Map<String, List<JsonNode>>> scenario : byScenario.entrySet()) {
            final StringBuilder row = new StringBuilder("| " + scenario.getKey());
            row.append(" | ").append(schedule(scenario.getValue().getOrDefault(baseline, List.of())));
            for (String c : candidates)
                row.append(" | ").append(schedule(scenario.getValue().getOrDefault(c, List.of())));
            md.println(row + " |");
        }
        md.println();
    }

    /** {@code on time}, {@code late to send: p99 +x ms[, max +y ms]}, or empty without runs that have both. */
    static String schedule(List<JsonNode> runs) {
        final List<String> late = new ArrayList<>();
        for (String q : new String[] {"p99", "max"}) {
            final Metric t = latency("transport." + q);
            final Metric s = latency("schedule." + q);
            final List<JsonNode> both = runs.stream().filter(r -> value(r, t) != null && value(r, s) != null)
                    .collect(Collectors.toList());
            if (both.isEmpty())
                return "";
            final double transport = pooledOrMean(both, t);
            final double gap = pooledOrMean(both, s) - transport;
            if (gap > transport * ON_SCHEDULE)
                late.add(String.format(Locale.ROOT, "%s +%.2f ms", q, gap));
        }
        return late.isEmpty() ? "on time" : "late to send: " + String.join(", ", late);
    }

    private static Metric latency(String path) {
        for (Metric m : METRICS.get("latency")) {
            if (m.path.equals(path))
                return m;
        }
        throw new IllegalArgumentException("No latency metric " + path);
    }

    private static Double value(JsonNode run, Metric metric) {
        JsonNode node = run.path("metrics");
        for (String part : metric.path.split("\\."))
            node = node.path(part);
        if (node.isBoolean())
            return node.asBoolean() ? 1.0 : 0.0;
        return node.isNumber() ? node.asDouble() * metric.scale : null;
    }

    private static Stats stats(List<JsonNode> runs, Metric metric) {
        final double[] values = runs.stream().map(r -> value(r, metric)).filter(v -> v != null).mapToDouble(Double::doubleValue).toArray();
        return values.length > 0 ? Stats.of(values) : null;
    }

    /** Paired by repetition: {wins, pairs}. */
    private static int[] wins(List<JsonNode> base, List<JsonNode> cand, Metric metric) {
        final Map<Integer, Double> b = new TreeMap<>();
        for (JsonNode r : base) {
            final Double v = value(r, metric);
            if (v != null)
                b.put(r.path("repetition").asInt(), v);
        }
        int wins = 0;
        int pairs = 0;
        for (JsonNode r : cand) {
            final Double v = value(r, metric);
            final Double other = b.get(r.path("repetition").asInt());
            if (v == null || other == null)
                continue;
            pairs++;
            if (metric.higherIsBetter ? v > other : v < other)
                wins++;
        }
        return new int[] {wins, pairs};
    }

    /**
     * Mean ± std dev (CV), or with the pooled value the pooled value and then the runs' mean ± std dev (CV); the
     * number of runs only when fewer than the repetitions planned.
     */
    private static String cell(Stats s, Double pooled, Metric metric, int planned) {
        if (s == null)
            return "";
        final String number = metric.scale < 1 ? "%.2f" : "%.1f";
        final String runs = String.format(Locale.ROOT, number + " ± " + number + " (%.0f %%)", s.mean, s.std, s.cv());
        return (pooled != null ? String.format(Locale.ROOT, number + ", runs ", pooled) + runs : runs)
                + (s.n < planned ? " n=" + s.n + " of " + planned : "");
    }

    private static String csvLine(String suite, String scenario, Metric metric, String server, Stats s, Double pooled,
                                  Double diff, int wins, int pairs) {
        return String.join(",", suite, "\"" + scenario + "\"", metric.path, server, Integer.toString(s.n),
                num(s.mean), num(s.std), num(s.cv()), num(s.median), num(s.q1), num(s.q3), num(s.min), num(s.max),
                diff != null ? num(diff) : "",
                wins >= 0 ? Integer.toString(wins) : "", wins >= 0 ? Integer.toString(pairs) : "",
                pooled != null ? num(pooled) : "");
    }

    /**
     * A percentile or the max over the merged histograms of the runs, as JMH computes percentiles over the samples
     * of all its iterations; {@code null} when the metric is not one or a run that has the metric has no histogram.
     */
    private static Double pooled(List<JsonNode> runs, Metric metric) {
        final int dot = metric.path.indexOf('.');
        final String stat = metric.path.substring(dot + 1);
        final double percentile;
        if (stat.equals("max"))
            percentile = 100;
        else if (stat.matches("p\\d+(_\\d+)?"))
            percentile = Double.parseDouble(stat.substring(1).replace('_', '.'));
        else
            return null;
        Histogram all = null;
        for (JsonNode run : runs) {
            if (value(run, metric) == null)
                continue;
            final JsonNode encoded = run.path("metrics").path(metric.path.substring(0, dot)).path("histogram");
            if (!encoded.isTextual())
                return null;
            final Histogram h = Output.decode(encoded.asText());
            if (all == null)
                all = h;
            else
                all.add(h);
        }
        if (all == null)
            return null;
        return (percentile == 100 ? all.getMaxValue() : all.getValueAtPercentile(percentile)) / 1e3 * metric.scale;
    }

    /** The pooled value if every run has a histogram, otherwise the mean over the runs. */
    private static double pooledOrMean(List<JsonNode> runs, Metric metric) {
        final Double pooled = pooled(runs, metric);
        return pooled != null ? pooled : stats(runs, metric).mean;
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
