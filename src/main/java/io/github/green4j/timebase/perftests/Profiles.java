package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a campaign from a profile: what to measure, given by {@code --profile} as the name of a bundled profile
 * or the path of a JSON file in the same format. {@code --depth}, {@code --load} and {@code --topology} choose how
 * deep, at which loads and with how many readers; {@code --set} replaces axes and parameters, {@code --baseline}
 * and {@code --candidate} give the servers.
 */
final class Profiles {

    static final List<String> NAMES = List.of("latency", "throughput", "correctness", "sanity");
    static final Map<String, Integer> TOPOLOGIES = topologies();
    static final String DEFAULT_DEPTH = "fast";
    static final String DEFAULT_TOPOLOGY = "one2three";
    /** Parameters that choose the configurations of a run; the runner reads them, the clients do not get them. */
    static final String SERVER_CONFIGURATION = "serverConfig";
    static final String CLIENT_CONFIGURATION = "clientConfig";

    /** Parameters each suite type understands. */
    static final Map<String, Set<String>> KEYS = Map.of(
            "latency", Set.of("mode", "rate", "burst", "readers", "symbols", "payloadBytes", "warmupMessages",
                    "warmupSeconds", "measureSeconds", "measureMessages", "drainSeconds"),
            "throughput", Set.of("mode", "readers", "symbols", "payloadBytes", "warmupSeconds", "measureSeconds",
                    "drainSeconds", "raw"),
            "correctness", Set.of("mode", "total", "liveReaders", "symbols", "burst", "pauseMillis", "timeoutSeconds",
                    "clientTimeoutSeconds"));

    private static final Set<String> PROFILE_KEYS = Set.of("name", "description", "type", "topology", "seed", "loads",
            "params", "matrix", "depths", "repetitions", "suites");
    private static final Set<String> DEPTH_KEYS = Set.of("suites", "repetitions", "loads", "params", "matrix");
    private static final Set<String> SUITE_KEYS = Set.of("name", "type", "repetitions", "params", "matrix");

    private static final Pattern SERVER = Pattern.compile("(?:([A-Za-z0-9_.-]+)=)?(dist|git|image|url):(.+)");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    private Profiles() {
    }

    private static Map<String, Integer> topologies() {
        final Map<String, Integer> t = new LinkedHashMap<>();
        t.put("one2one", 1);
        t.put("one2three", 3);
        t.put("one2six", 6);
        return Collections.unmodifiableMap(t);
    }

    static JsonNode load(String name) throws IOException {
        try (InputStream in = Profiles.class.getResourceAsStream("profiles/" + name + ".json")) {
            if (in == null)
                throw new IllegalArgumentException("No profile " + name);
            return Output.JSON.readTree(in);
        }
    }

    /**
     * A file profile: a path ending in {@code .json} or containing a separator. {@code ${NAME}} in it is replaced
     * with an environment variable.
     */
    static JsonNode loadFile(Path file) throws IOException {
        return Output.JSON.readTree(Runner.substituteEnvironment(Files.readString(file), System.getenv()));
    }

    static boolean isFile(String profile) {
        return profile.endsWith(".json") || profile.indexOf('/') >= 0 || profile.indexOf(java.io.File.separatorChar) >= 0;
    }

    static ObjectNode generate(Args args) throws IOException {
        final String spec = args.string("profile");
        final boolean file = isFile(spec);
        if (!file && !NAMES.contains(spec))
            throw new IllegalArgumentException("profile must be one of " + NAMES + " or a .json file, got " + spec);
        final JsonNode profile = file ? loadFile(Path.of(spec)) : load(spec);
        final String name = file
                ? profile.path("name").asText(Path.of(spec).getFileName().toString().replaceFirst("\\.json$", ""))
                : spec;
        only(profile, PROFILE_KEYS, "Profile " + name);
        if (profile.has("depths") && (profile.has("repetitions") || profile.has("suites")))
            throw new IllegalArgumentException("Profile " + name + " with depths gives repetitions and suites in its depths");
        final JsonNode loadRates = profile.path("loads");
        final JsonNode depths = profile.has("depths") ? profile.get("depths") : singleDepth(profile);
        validate(profile, depths, name);

        String depth = args.string("depth", null);
        if (depth == null)
            depth = depths.size() == 1 ? depths.fieldNames().next() : DEFAULT_DEPTH;
        if (!depths.has(depth))
            throw new IllegalArgumentException("Profile " + name + " has no depth " + depth + "; depths " + fieldNames(depths));

        final List<String> loads = new ArrayList<>();
        if (args.has("load")) {
            for (String l : args.string("load").split(",")) {
                final String load = l.trim();
                if (!loadRates.has(load))
                    throw new IllegalArgumentException("Profile " + name + " has no load " + load + "; loads " + fieldNames(loadRates));
                if (loads.contains(load))
                    throw new IllegalArgumentException("load names " + load + " twice");
                loads.add(load);
            }
        }

        String topology = args.string("topology", null);
        if (topology != null && !TOPOLOGIES.containsKey(topology))
            throw new IllegalArgumentException("topology must be one of " + TOPOLOGIES.keySet() + ", got " + topology);
        final boolean topologyGiven = topology != null;
        if (topology == null)
            topology = profile.path("topology").asText(DEFAULT_TOPOLOGY);

        final BenchFile bench = args.has("bench") ? BenchFile.load(Path.of(args.string("bench"))) : null;
        final ArrayNode servers = servers(args, bench);
        final ObjectNode clients = bench != null ? bench.client.deepCopy() : BenchFile.single(BenchFile.defaultClient());
        for (JsonNode v : servers) {
            if (v.hasNonNull("url"))
                continue;
            final boolean local = !v.hasNonNull("image");
            v.get("configurations").forEach(c -> {
                if (args.has("port"))
                    ((ObjectNode) c).put("port", args.integer("port"));
                if (args.has("serverJava") && local)
                    ((ObjectNode) c).put("java", args.string("serverJava"));
            });
        }
        if (args.has("clientJava"))
            clients.forEach(c -> ((ObjectNode) c).put("java", args.string("clientJava")));
        final ObjectNode campaign = Output.JSON.createObjectNode();
        campaign.put("campaign", "");
        campaign.put("seed", args.longValue("seed", profile.path("seed").asLong(1)));
        campaign.put("baseline", servers.get(0).path("id").asText());
        campaign.set("servers", servers);
        campaign.set("clients", clients);
        if (bench != null)
            campaign.put("benchFile", Path.of(args.string("bench")).toAbsolutePath().normalize().toString());

        final JsonNode preset = depths.get(depth);
        final boolean loadsGiven = !loads.isEmpty();
        if (!loadsGiven)
            preset.path("loads").forEach(l -> loads.add(l.asText()));
        loads.sort((a, b) -> Long.compare(loadRates.path(a).asLong(), loadRates.path(b).asLong()));
        final ArrayNode suites = campaign.putArray("suites");
        boolean hasReaders = false;
        for (JsonNode s : preset.path("suites")) {
            final String type = s.path("type").asText(profile.path("type").asText());
            final Set<String> keys = KEYS.get(type);
            final ObjectNode suite = suites.addObject();
            suite.put("name", s.path("name").asText(type));
            suite.put("type", type);
            suite.put("repetitions", s.path("repetitions").asInt(preset.path("repetitions").asInt(1)));
            ObjectNode matrix = Output.JSON.createObjectNode();
            final ObjectNode params = Output.JSON.createObjectNode();
            layer(profile, matrix, params, keys);
            layer(preset, matrix, params, keys);
            layer(s, matrix, params, keys);
            if (!loads.isEmpty() && keys.contains("rate")) {
                params.remove("rate");
                matrix.remove("rate");
                final ObjectNode withRate = Output.JSON.createObjectNode();
                final ArrayNode rate = withRate.putArray("rate");
                loads.forEach(l -> rate.add(loadRates.path(l).asLong()));
                withRate.setAll(matrix);
                matrix = withRate;
            }
            if (keys.contains("readers")) {
                hasReaders = true;
                matrix.remove("readers");
                params.put("readers", TOPOLOGIES.get(topology));
            }
            configurationAxis(matrix, params, SERVER_CONFIGURATION, servers.get(0).get("configurations"));
            configurationAxis(matrix, params, CLIENT_CONFIGURATION, clients);
            suite.set("matrix", matrix);
            suite.set("params", params);
        }
        if (topologyGiven && !hasReaders)
            throw new IllegalArgumentException("Profile " + name + " takes no topology: none of its suites has readers");

        final ArrayNode overrides = Output.JSON.createArrayNode();
        for (String set : args.all("set")) {
            override(suites, set, name);
            overrides.add(set);
            final String key = set.substring(0, set.indexOf('='));
            if (key.equals("readers") || key.endsWith(".readers"))
                topology = "readers" + set.substring(set.indexOf('=') + 1).replace(',', '-');
        }

        final List<String> ids = new ArrayList<>();
        servers.forEach(v -> ids.add(v.path("id").asText()));
        campaign.put("campaign", args.string("name", name + "-" + (depths.size() > 1 ? depth + "-" : "")
                + (loadsGiven ? String.join("-", loads) + "-" : "") + (hasReaders ? topology + "-" : "")
                + String.join("-vs-", ids)));

        final ObjectNode meta = campaign.putObject("profile");
        meta.put("name", name);
        if (file)
            meta.put("file", Path.of(spec).toAbsolutePath().normalize().toString());
        meta.put("depth", depth);
        if (hasReaders)
            meta.put("topology", topology);
        final ArrayNode loadsMeta = meta.putArray("loads");
        loads.forEach(loadsMeta::add);
        meta.set("overrides", overrides);
        meta.set("definition", profile.deepCopy());
        return campaign;
    }

    /** Several configurations are an axis, so that the runs of every server cover each; one is a parameter. */
    private static void configurationAxis(ObjectNode matrix, ObjectNode params, String key, JsonNode configurations) {
        final List<String> ids = fieldNames(configurations);
        if (ids.size() == 1) {
            params.put(key, ids.get(0));
        } else {
            final ArrayNode axis = matrix.putArray(key);
            ids.forEach(axis::add);
        }
    }

    /** A profile without {@code depths} is one depth: its own {@code repetitions} and {@code suites}, all its loads. */
    private static JsonNode singleDepth(JsonNode profile) {
        final ObjectNode depths = Output.JSON.createObjectNode();
        final ObjectNode only = depths.putObject("default");
        only.put("repetitions", profile.path("repetitions").asInt(1));
        only.set("suites", profile.has("suites") ? profile.get("suites").deepCopy() : Output.JSON.createArrayNode().add(Output.JSON.createObjectNode()));
        final ArrayNode loads = only.putArray("loads");
        profile.path("loads").fieldNames().forEachRemaining(loads::add);
        return depths;
    }

    /** {@code key=v1[,v2...]} or {@code suite.key=...}: one value is a parameter, several replace a matrix axis. */
    static void override(ArrayNode suites, String set, String profile) {
        final int eq = set.indexOf('=');
        if (eq <= 0)
            throw new IllegalArgumentException("Expected --set=key=value[,value...], got " + set);
        String key = set.substring(0, eq);
        String scope = null;
        final int dot = key.indexOf('.');
        if (dot > 0) {
            scope = key.substring(0, dot);
            key = key.substring(dot + 1);
        }
        final String[] values = set.substring(eq + 1).split(",");
        boolean applied = false;
        for (JsonNode node : suites) {
            final ObjectNode suite = (ObjectNode) node;
            if (scope != null && !scope.equals(suite.path("name").asText()))
                continue;
            if ("repetitions".equals(key)) {
                if (values.length != 1 || !INTEGER.matcher(values[0]).matches() || Integer.parseInt(values[0]) < 1)
                    throw new IllegalArgumentException("repetitions must be one positive integer, got " + set.substring(eq + 1));
                suite.put("repetitions", Integer.parseInt(values[0]));
                applied = true;
                continue;
            }
            if (!KEYS.get(suite.path("type").asText()).contains(key))
                continue;
            final ObjectNode matrix = (ObjectNode) suite.get("matrix");
            final ObjectNode params = (ObjectNode) suite.get("params");
            matrix.remove(key);
            params.remove(key);
            if (values.length == 1) {
                put(params, key, values[0]);
            } else {
                final ArrayNode axis = matrix.putArray(key);
                for (String v : values)
                    add(axis, v);
            }
            applied = true;
        }
        if (!applied)
            throw new IllegalArgumentException("No suite of profile " + profile + (scope != null ? " named " + scope : "")
                    + " accepts " + key);
    }

    /**
     * {@code --baseline} alone measures one server; with {@code --candidate} the servers are compared; the
     * {@code baseline} and {@code candidates} of a bench file replace both.
     */
    static ArrayNode servers(Args args, BenchFile bench) {
        if (bench != null && bench.servers != null) {
            if (args.has("baseline") || args.has("candidate"))
                throw new IllegalArgumentException("The bench file gives the servers; give no --baseline or --candidate");
            return bench.servers.deepCopy();
        }
        final ArrayNode servers = Output.JSON.createArrayNode();
        servers.add(server(args.string("baseline"), "orig"));
        final List<String> candidates = args.all("candidate");
        for (String c : candidates)
            servers.add(server(c, candidates.size() == 1 ? "patched" : null));
        for (JsonNode s : servers)
            ((ObjectNode) s).set("configurations", BenchFile.single(s.hasNonNull("url") ? Output.JSON.createObjectNode() : BenchFile.defaultServer()));
        return servers;
    }

    /** {@code [id=]dist:<dir>[+overlay:<jar>]}, {@code [id=]git:<repo>@<ref>}, {@code [id=]image:<tag>}, {@code [id=]url:<url>}. */
    static ObjectNode server(String spec, String defaultId) {
        final Matcher m = SERVER.matcher(spec);
        if (!m.matches())
            throw new IllegalArgumentException("Expected [id=]dist:|git:|image:|url:..., got " + spec);
        final String id = m.group(1) != null ? m.group(1) : defaultId;
        if (id == null)
            throw new IllegalArgumentException("Give an id to every candidate when there are several: id=" + spec);
        final ObjectNode v = Output.JSON.createObjectNode();
        v.put("id", id);
        final String value = m.group(3);
        switch (m.group(2)) {
            case "dist": {
                final int plus = value.indexOf("+overlay:");
                final String dist = plus < 0 ? value : value.substring(0, plus);
                final String overlay = plus < 0 ? null : value.substring(plus + "+overlay:".length());
                if (dist.isEmpty() || (overlay != null && overlay.isEmpty()))
                    throw new IllegalArgumentException("Expected dist:<dir>[+overlay:<jar>], got " + spec);
                v.put("dist", dist);
                if (overlay != null)
                    v.put("overlay", overlay);
                break;
            }
            case "git": {
                final int at = value.lastIndexOf('@');
                if (at <= 0 || at == value.length() - 1)
                    throw new IllegalArgumentException("Expected git:<repo>@<ref>, got " + spec);
                final ObjectNode git = v.putObject("git");
                git.put("repo", value.substring(0, at));
                git.put("ref", value.substring(at + 1));
                break;
            }
            case "image":
                v.put("image", value);
                break;
            default:
                v.put("url", value);
        }
        return v;
    }

    /** Lists every profile with its depths, as a table for people. */
    static void list() throws IOException {
        for (String name : NAMES) {
            final JsonNode p = load(name);
            System.out.printf("%s: %s%n", name, p.path("description").asText());
            if (p.has("loads"))
                System.out.printf("  loads    %s msg/s%n", p.get("loads"));
            final Iterator<Map.Entry<String, JsonNode>> depths = p.path("depths").fields();
            while (depths.hasNext()) {
                final Map.Entry<String, JsonNode> d = depths.next();
                final List<String> suites = new ArrayList<>();
                d.getValue().path("suites").forEach(s -> suites.add(s.path("name").asText() + " " + s.path("matrix")));
                System.out.printf("  %-8s %d repetitions, %s%s, %s%n", d.getKey(), d.getValue().path("repetitions").asInt(1),
                        d.getValue().has("loads") ? "loads " + d.getValue().get("loads") + ", " : "",
                        d.getValue().path("params"), String.join("; ", suites));
            }
        }
        System.out.println("Topologies: " + TOPOLOGIES + ", default topology: " + DEFAULT_TOPOLOGY + ", default depth: " + DEFAULT_DEPTH);
        System.out.println("A profile file in the same format is given as --profile=<file.json>");
    }

    /** Checks every depth and suite, not only the ones chosen, so that a mistake does not wait for another depth. */
    private static void validate(JsonNode profile, JsonNode depths, String name) {
        parameters(profile, name);
        depths.fields().forEachRemaining(d -> {
            only(d.getValue(), DEPTH_KEYS, "Depth " + d.getKey() + " of profile " + name);
            parameters(d.getValue(), name);
            for (JsonNode s : d.getValue().path("suites")) {
                only(s, SUITE_KEYS, "A suite of profile " + name);
                parameters(s, name);
                final String type = s.path("type").asText(profile.path("type").asText());
                if (!KEYS.containsKey(type))
                    throw new IllegalArgumentException("Unknown suite type " + type + " in profile " + name);
            }
        });
    }

    /** The {@code matrix} and {@code params} of a profile, depth or suite: known keys, none in both. */
    private static void parameters(JsonNode from, String profile) {
        from.path("matrix").fieldNames().forEachRemaining(k -> {
            if (from.path("params").has(k))
                throw new IllegalArgumentException("Profile " + profile + " gives " + k + " both as an axis and as a parameter at one level");
        });
        for (String kind : new String[] {"matrix", "params"}) {
            from.path(kind).fieldNames().forEachRemaining(k -> {
                if (KEYS.values().stream().noneMatch(s -> s.contains(k)))
                    throw new IllegalArgumentException("Profile " + profile + " has the unknown key " + k + " in " + kind);
            });
        }
    }

    /** Applies the {@code matrix} and {@code params} of a profile, depth or suite: a key it gives replaces the key of either kind. */
    private static void layer(JsonNode from, ObjectNode matrix, ObjectNode params, Set<String> keys) {
        from.path("matrix").fields().forEachRemaining(e -> {
            if (keys.contains(e.getKey())) {
                params.remove(e.getKey());
                matrix.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        from.path("params").fields().forEachRemaining(e -> {
            if (keys.contains(e.getKey())) {
                matrix.remove(e.getKey());
                params.set(e.getKey(), e.getValue().deepCopy());
            }
        });
    }

    private static void put(ObjectNode node, String key, String value) {
        if (INTEGER.matcher(value).matches())
            node.put(key, Long.parseLong(value));
        else
            node.put(key, value);
    }

    private static void add(ArrayNode node, String value) {
        if (INTEGER.matcher(value).matches())
            node.add(Long.parseLong(value));
        else
            node.add(value);
    }

    private static void only(JsonNode node, Set<String> allowed, String what) {
        node.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k))
                throw new IllegalArgumentException(what + " has the unknown key " + k + "; keys " + new java.util.TreeSet<>(allowed));
        });
    }

    private static List<String> fieldNames(JsonNode node) {
        final List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
