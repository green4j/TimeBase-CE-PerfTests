package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Expands a campaign into runs. Order: repetition, then scenarios in a seeded random order, then the servers of one
 * scenario in a seeded random order, so every scenario/repetition block compares all servers back to back.
 */
final class Plan {

    static final class Run {
        final String id;
        final String suite;
        final String type;
        final String server;
        final int repetition;
        final Map<String, String> params;

        Run(String id, String suite, String type, String server, int repetition, Map<String, String> params) {
            this.id = id;
            this.suite = suite;
            this.type = type;
            this.server = server;
            this.repetition = repetition;
            this.params = params;
        }

        ObjectNode toJson() {
            final ObjectNode node = Output.JSON.createObjectNode();
            node.put("id", id);
            node.put("suite", suite);
            node.put("type", type);
            node.put("server", server);
            node.put("repetition", repetition);
            final ObjectNode p = node.putObject("params");
            params.forEach(p::put);
            return node;
        }
    }

    private Plan() {
    }

    static List<Run> expand(JsonNode campaign) {
        final long seed = campaign.path("seed").asLong(1);
        final List<String> servers = new ArrayList<>();
        for (JsonNode v : campaign.path("servers")) {
            final String id = v.path("id").asText();
            if (id.isEmpty() || servers.contains(id))
                throw new IllegalArgumentException("Server ids must be unique and not empty, got '" + id + "'");
            servers.add(id);
        }
        if (servers.isEmpty())
            throw new IllegalArgumentException("At least one server is required");

        final List<JsonNode> suites = new ArrayList<>();
        campaign.path("suites").forEach(suites::add);
        int maxRepetitions = 0;
        for (JsonNode suite : suites)
            maxRepetitions = Math.max(maxRepetitions, suite.path("repetitions").asInt(1));

        final Random random = new Random(seed);
        final List<Run> runs = new ArrayList<>();
        for (int rep = 1; rep <= maxRepetitions; rep++) {
            final List<String[]> blocks = new ArrayList<>();
            final List<Map<String, String>> blockParams = new ArrayList<>();
            final List<List<String>> axes = new ArrayList<>();
            for (JsonNode suite : suites) {
                if (rep > suite.path("repetitions").asInt(1))
                    continue;
                final List<String> names = new ArrayList<>();
                suite.path("matrix").fieldNames().forEachRemaining(names::add);
                for (Map<String, String> scenario : scenarios(suite)) {
                    blocks.add(new String[] {suite.path("name").asText(), suite.path("type").asText()});
                    blockParams.add(scenario);
                    axes.add(names);
                }
            }
            final List<Integer> order = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++)
                order.add(i);
            Collections.shuffle(order, random);
            for (int b : order) {
                final List<String> shuffled = new ArrayList<>(servers);
                Collections.shuffle(shuffled, random);
                for (String server : shuffled) {
                    final String[] block = blocks.get(b);
                    final Map<String, String> params = blockParams.get(b);
                    runs.add(new Run(runId(block[0], axes.get(b), params, rep, server), block[0], block[1], server, rep, params));
                }
            }
        }
        if (runs.isEmpty())
            throw new IllegalArgumentException("The campaign has no runs");
        final Set<String> ids = new HashSet<>();
        for (Run run : runs) {
            if (!ids.add(run.id))
                throw new IllegalArgumentException("Two runs get the id " + run.id
                        + "; suite names and matrix values must stay distinct after replacing characters other than [A-Za-z0-9_.-]");
        }
        return runs;
    }

    /** Approximate wall time of the runs: server start and stop plus the configured phases. */
    static long estimateSeconds(List<Run> runs) {
        long total = 0;
        for (Run run : runs) {
            final Map<String, String> p = run.params;
            total += SERVER_OVERHEAD_SECONDS;
            switch (run.type) {
                case "latency":
                    total += number(p, "warmupMessages", LatencyCommand.DEFAULT_WARMUP_MESSAGES) / WARMUP_RATE + 1
                            + number(p, "warmupSeconds", LatencyCommand.DEFAULT_WARMUP_SECONDS)
                            + LatencyCommand.measureCount(p) / Math.max(1, number(p, "rate", 200_000)) + 1;
                    break;
                case "throughput":
                    total += number(p, "warmupSeconds", 5) + number(p, "measureSeconds", 10) + 3;
                    break;
                default:
                    total += 8;
            }
        }
        return total;
    }

    private static final int SERVER_OVERHEAD_SECONDS = 4;
    /** Messages per second a latency warmup is assumed to be sent and read at. */
    private static final long WARMUP_RATE = 200_000;

    /** A numeric run parameter, or {@code defaultValue} when the run does not give it. */
    static long number(Map<String, String> p, String name, long defaultValue) {
        final String value = p.get(name);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    /** {@code a * b}, {@link Long#MAX_VALUE} on overflow; a negative factor is an error. */
    static long times(long a, long b) {
        if (a < 0 || b < 0)
            throw new IllegalArgumentException("Negative run parameter: " + a + " x " + b);
        return b != 0 && a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /** Cartesian product of {@code matrix} axes, each combined with the fixed {@code params}. */
    static List<Map<String, String>> scenarios(JsonNode suite) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        final Iterator<Map.Entry<String, JsonNode>> axes = suite.path("matrix").fields();
        while (axes.hasNext()) {
            final Map.Entry<String, JsonNode> axis = axes.next();
            final List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : result) {
                for (JsonNode value : axis.getValue()) {
                    final Map<String, String> m = new LinkedHashMap<>(partial);
                    m.put(axis.getKey(), value.asText());
                    next.add(m);
                }
            }
            result = next;
        }
        final Iterator<Map.Entry<String, JsonNode>> fixed = suite.path("params").fields();
        while (fixed.hasNext()) {
            final Map.Entry<String, JsonNode> p = fixed.next();
            for (Map<String, String> m : result)
                m.putIfAbsent(p.getKey(), p.getValue().asText());
        }
        return result;
    }

    static String runId(String suite, List<String> axes, Map<String, String> params, int repetition, String server) {
        final StringBuilder sb = new StringBuilder(suite);
        for (String axis : axes)
            sb.append('_').append(axis).append('-').append(params.get(axis));
        sb.append(String.format("_r%02d_", repetition)).append(server);
        return sb.toString().replaceAll("[^A-Za-z0-9_.-]", "-");
    }
}
