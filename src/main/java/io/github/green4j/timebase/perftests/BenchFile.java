package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The test bench, {@code --bench=<file.json>}: the code measured and the configurations it runs in.
 * {@code {"baseline": {...}, "candidates": [{...}], "client": {"configurations": [...]}}}; a server is an id, one of
 * git, dist, image, url and its {@code configurations}. A candidate is compared with the baseline in the
 * configuration of the same id. Paths are relative to the file; {@code ${NAME}} is replaced with an environment
 * variable.
 */
final class BenchFile {

    static final String DEFAULT_CONFIGURATION = "default";

    /** The servers, the baseline first, each with its configurations by id, or {@code null} when the command line gives them. */
    final ArrayNode servers;
    /** The client configurations by id. */
    final ObjectNode client;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final List<String> KINDS = List.of("git", "dist", "image", "url");
    private static final Map<String, Set<String>> KEYS = Map.of(
            "git", Set.of("id", "git", "overlay", "configurations"),
            "dist", Set.of("id", "dist", "overlay", "configurations"),
            "image", Set.of("id", "image", "containerPort", "configurations"),
            "url", Set.of("id", "url", "configurations"));
    private static final Set<String> GIT_KEYS = Set.of("repo", "ref", "javaHome", "task", "output", "gradleArgs");
    private static final Set<String> PROCESS_KEYS = Set.of("id", "port", "webPort", "java", "jvmArgs", "prefix", "startTimeoutSeconds");
    private static final Map<String, Set<String>> CONFIGURATION_KEYS = Map.of(
            "git", PROCESS_KEYS,
            "dist", PROCESS_KEYS,
            "image", Set.of("id", "port", "jvmArgs", "dockerArgs", "startTimeoutSeconds"),
            "url", Set.of("id"));
    private static final Set<String> CLIENT_KEYS = Set.of("id", "java", "jvmArgs", "prefix");
    /** Docker options the runner sets itself. */
    private static final List<String> RUNNER_DOCKER_OPTIONS = List.of("--name", "-p", "--publish", "-P", "--publish-all",
            "-d", "--detach", "--rm", "--entrypoint");

    private BenchFile(ArrayNode servers, ObjectNode client) {
        this.servers = servers;
        this.client = client;
    }

    static ObjectNode defaultServer() {
        final ObjectNode c = Output.JSON.createObjectNode();
        c.put("port", 18011);
        c.putArray("jvmArgs").add("-Xmx4g").add("-Xms4g");
        return c;
    }

    static ObjectNode defaultClient() {
        final ObjectNode c = Output.JSON.createObjectNode();
        c.putArray("jvmArgs").add("-Xmx2g").add("-Xms2g");
        return c;
    }

    /** One configuration, {@code default}, with the defaults. */
    static ObjectNode single(ObjectNode defaults) {
        final ObjectNode map = Output.JSON.createObjectNode();
        map.set(DEFAULT_CONFIGURATION, defaults);
        return map;
    }

    static BenchFile load(Path file) throws IOException {
        final JsonNode root = Output.JSON.readTree(Runner.substituteEnvironment(Files.readString(file), System.getenv()));
        final Path dir = file.toAbsolutePath().normalize().getParent();
        only(root, Set.of("description", "baseline", "candidates", "client"), "The bench file " + file);
        final ObjectNode client;
        if (root.has("client")) {
            final JsonNode c = root.get("client");
            if (!c.isObject())
                throw new IllegalArgumentException("client must be an object in " + file);
            only(c, Set.of("configurations"), "The bench file " + file + " client");
            client = configurations(c, CLIENT_KEYS, defaultClient(), "client");
        } else {
            client = single(defaultClient());
        }
        if (!root.has("baseline")) {
            if (root.has("candidates"))
                throw new IllegalArgumentException("candidates need a baseline to be compared with in " + file);
            return new BenchFile(null, client);
        }
        return new BenchFile(servers(root, dir, file), client);
    }

    /** The baseline first, then the candidates; ids default as on the command line. */
    private static ArrayNode servers(JsonNode root, Path dir, Path file) {
        final JsonNode candidates = root.has("candidates") ? root.get("candidates") : Output.JSON.createArrayNode();
        if (!candidates.isArray())
            throw new IllegalArgumentException("candidates must be an array of servers in " + file);
        final ArrayNode servers = Output.JSON.createArrayNode();
        servers.add(server(root.get("baseline"), dir, "orig"));
        for (JsonNode c : candidates)
            servers.add(server(c, dir, candidates.size() == 1 ? "patched" : null));
        final Set<String> ids = new HashSet<>();
        final Set<String> configurations = fieldNames(servers.get(0).get("configurations"));
        for (JsonNode s : servers) {
            final String id = s.get("id").asText();
            if (!ids.add(id))
                throw new IllegalArgumentException("Two servers have the id " + id + " in " + file);
            if (!fieldNames(s.get("configurations")).equals(configurations))
                throw new IllegalArgumentException("Server " + id + " has the configurations " + fieldNames(s.get("configurations"))
                        + " but the baseline " + configurations + ": a candidate is compared with the baseline configuration of the same id");
        }
        return servers;
    }

    private static ObjectNode server(JsonNode v, Path dir, String defaultId) {
        if (!v.isObject())
            throw new IllegalArgumentException("A server must be an object, got " + v);
        final String id = id(v, defaultId, "Give every candidate an id when there are several: " + v);
        final List<String> kinds = new ArrayList<>();
        for (String k : KINDS) {
            if (v.has(k))
                kinds.add(k);
        }
        if (kinds.size() != 1)
            throw new IllegalArgumentException("Server " + id + " must have exactly one of " + KINDS + ", got " + kinds);
        final String kind = kinds.get(0);
        only(v, KEYS.get(kind), "Server " + id);

        final ObjectNode server = v.deepCopy();
        server.put("id", id);
        switch (kind) {
            case "git": {
                final JsonNode git = v.get("git");
                if (!git.isObject())
                    throw new IllegalArgumentException("Server " + id + ": git must be an object");
                only(git, GIT_KEYS, "Server " + id + " git");
                if (!git.hasNonNull("repo") || !git.hasNonNull("ref"))
                    throw new IllegalArgumentException("Server " + id + " needs git.repo and git.ref");
                strings(git, "gradleArgs", id);
                ((ObjectNode) server.get("git")).put("repo", path(dir, git.get("repo").asText()));
                break;
            }
            case "dist":
                server.put("dist", path(dir, text(v, "dist", id)));
                break;
            case "image":
                text(v, "image", id);
                integer(v, "containerPort", 1, 65535, "Server " + id);
                break;
            default:
                text(v, "url", id);
        }
        if (v.has("overlay"))
            server.put("overlay", path(dir, text(v, "overlay", id)));
        final ObjectNode configurations = configurations(v, CONFIGURATION_KEYS.get(kind),
                "url".equals(kind) ? Output.JSON.createObjectNode() : defaultServer(), "Server " + id);
        configurations.fields().forEachRemaining(e -> dockerArgs(e.getValue().path("dockerArgs"), id + "/" + e.getKey()));
        server.set("configurations", configurations);
        return server;
    }

    /** The configurations of a server or of the client by id, each over the defaults; one {@code default} without any. */
    private static ObjectNode configurations(JsonNode owner, Set<String> keys, ObjectNode defaults, String what) {
        if (!owner.has("configurations"))
            return single(defaults);
        final JsonNode list = owner.get("configurations");
        if (!list.isArray() || list.size() == 0)
            throw new IllegalArgumentException(what + ": configurations must list at least one configuration");
        final ObjectNode map = Output.JSON.createObjectNode();
        for (JsonNode c : list) {
            if (!c.isObject())
                throw new IllegalArgumentException(what + ": a configuration must be an object, got " + c);
            final String id = id(c, list.size() == 1 ? DEFAULT_CONFIGURATION : null,
                    what + ": give every configuration an id when there are several: " + c);
            only(c, keys, what + " configuration " + id);
            for (String key : new String[] {"jvmArgs", "prefix", "dockerArgs"})
                strings(c, key, what + "/" + id);
            for (String key : new String[] {"port", "webPort"})
                integer(c, key, 1, 65535, what + "/" + id);
            integer(c, "startTimeoutSeconds", 1, Integer.MAX_VALUE, what + "/" + id);
            if (c.has("java") && (!c.get("java").isTextual() || c.get("java").asText().isBlank()))
                throw new IllegalArgumentException(what + "/" + id + ": java must be a non-empty string");
            if (map.has(id))
                throw new IllegalArgumentException(what + " has two configurations " + id);
            final ObjectNode configuration = defaults.deepCopy();
            configuration.setAll((ObjectNode) c.deepCopy());
            configuration.remove("id");
            map.set(id, configuration);
        }
        return map;
    }

    private static String id(JsonNode v, String defaultId, String missing) {
        if (!v.has("id")) {
            if (defaultId == null)
                throw new IllegalArgumentException(missing);
            return defaultId;
        }
        if (!v.get("id").isTextual() || !ID.matcher(v.get("id").asText()).matches())
            throw new IllegalArgumentException("An id must be a string matching " + ID + ", got " + v.get("id"));
        return v.get("id").asText();
    }

    private static void dockerArgs(JsonNode args, String owner) {
        for (int i = 0; i < args.size(); i++) {
            final String a = args.get(i).asText();
            for (String option : RUNNER_DOCKER_OPTIONS) {
                if (a.equals(option) || a.startsWith(option + "="))
                    throw new IllegalArgumentException(owner + ": dockerArgs must not give " + option + ", the runner sets it");
            }
            final String env = a.equals("-e") || a.equals("--env") ? (i + 1 < args.size() ? args.get(i + 1).asText() : "")
                    : a.startsWith("--env=") ? a.substring("--env=".length()) : null;
            if (env != null && (env.equals("JAVA_OPTS") || env.startsWith("JAVA_OPTS=")))
                throw new IllegalArgumentException(owner + ": dockerArgs must not set JAVA_OPTS, give jvmArgs instead");
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        final Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String path(Path dir, String value) {
        final Path p = Path.of(value.replaceFirst("^~", System.getProperty("user.home")));
        return (p.isAbsolute() ? p : dir.resolve(p)).normalize().toString();
    }

    private static String text(JsonNode v, String key, String id) {
        final JsonNode value = v.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("Server " + id + ": " + key + " must be a non-empty string");
        return value.asText();
    }

    private static void integer(JsonNode v, String key, int min, int max, String owner) {
        if (!v.has(key))
            return;
        final JsonNode n = v.get(key);
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < min || n.asInt() > max)
            throw new IllegalArgumentException(owner + ": " + key + " must be an integer in [" + min + ", " + max + "], got " + n);
    }

    private static void strings(JsonNode v, String key, String owner) {
        if (!v.has(key))
            return;
        final JsonNode array = v.get(key);
        if (!array.isArray())
            throw new IllegalArgumentException(owner + ": " + key + " must be an array of strings");
        for (JsonNode s : array) {
            if (!s.isTextual() || s.asText().isBlank())
                throw new IllegalArgumentException(owner + ": " + key + " must hold non-empty strings, got " + s);
        }
    }

    private static void only(JsonNode node, Set<String> allowed, String what) {
        node.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k))
                throw new IllegalArgumentException(what + " has the unknown or inapplicable key " + k + "; allowed " + allowed);
        });
    }
}
