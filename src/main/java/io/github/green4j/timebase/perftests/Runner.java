package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Executes a campaign: a fresh server per run, the clients of the run's suite type, one JSON file per run.
 * A run whose file reports {@code ok} for the same definition is skipped, so an interrupted campaign resumes where
 * it stopped; a file made from a different definition stops the campaign instead of being reused.
 */
final class Runner {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JsonNode campaign;
    private final Path out;

    private Runner(JsonNode campaign, Path out) {
        this.campaign = campaign;
        this.out = out;
    }

    /** Returns whether every run of the campaign is {@code ok}. */
    static boolean run(Args args) throws Exception {
        final ObjectNode campaign = Profiles.generate(args);
        final ObjectNode generated = campaign.deepCopy();
        if (args.bool("print", false)) {
            System.out.println(Output.JSON.writeValueAsString(generated));
            return true;
        }
        final String name = campaign.path("campaign").asText();
        final Path root = Path.of(args.string("out", "results"));
        final Path out = root.resolve(name);
        final List<Plan.Run> plan = Plan.expand(campaign);
        System.out.printf("Campaign %s: %d runs, about %d min%n", name, plan.size(), (Plan.estimateSeconds(plan) + 59) / 60);

        if (args.bool("dryRun", false)) {
            for (Plan.Run run : plan)
                System.out.println(run.id);
            return true;
        }

        final Path cache = root.resolve(".builds");
        final Runner runner;
        try (FileChannel lock = ServerBuild.lock(cache)) {
            ServerBuild.resolve(campaign, cache, System.getenv());
            ServerBuild.prune(cache, root, campaign);
            for (JsonNode s : campaign.path("servers")) {
                final ObjectNode server = (ObjectNode) s;
                if (s.hasNonNull("image"))
                    server.put("imageId", DockerServer.imageId(s.get("image").asText()));
                // Part of every run's definition, so that a server replaced under the same path is a different one
                if (s.hasNonNull("dist"))
                    server.put("distSha256", treeSha256(Path.of(s.get("dist").asText()).resolve("lib")));
                if (s.hasNonNull("overlay"))
                    server.put("overlaySha256", sha256(Path.of(s.get("overlay").asText())));
            }
            campaign.put("suiteSha256", suiteDigest());
            runner = new Runner(campaign, out);
            runner.checkResumable(plan);

            Files.createDirectories(out.resolve("runs"));
            Files.createDirectories(out.resolve("logs"));
            writeAtomically(out.resolve("generated-campaign.json"), generated);
            writeAtomically(out.resolve("campaign.json"), campaign);
        }
        final ArrayNode expanded = Output.JSON.createArrayNode();
        plan.forEach(r -> expanded.add(r.toJson()));
        writeAtomically(out.resolve("expanded-plan.json"), expanded);
        writeAtomically(out.resolve("environment.json"), environment(campaign));

        final boolean ok = runner.execute(plan);
        Report.run(Args.parse(new String[] {"--results=" + out}));
        return ok;
    }

    private void checkResumable(List<Plan.Run> plan) throws Exception {
        final List<String> conflicts = new ArrayList<>();
        final Set<String> planned = new HashSet<>();
        for (Plan.Run run : plan) {
            planned.add(run.id + ".json");
            final Path file = runFile(run);
            if (Files.exists(file) && !definitionHash(run).equals(Output.JSON.readTree(file.toFile()).path("definitionSha256").asText()))
                conflicts.add(run.id);
        }
        final Path runs = out.resolve("runs");
        if (Files.isDirectory(runs)) {
            try (Stream<Path> files = Files.list(runs)) {
                files.map(f -> f.getFileName().toString())
                        .filter(f -> f.endsWith(".json") && !planned.contains(f))
                        .forEach(f -> conflicts.add(f + " (not in the plan)"));
            }
        }
        if (!conflicts.isEmpty())
            throw new IllegalStateException(conflicts.size() + " run files in " + out + " were made from a different campaign, "
                    + "server, build or suite version (first: " + conflicts.get(0) + "); use another campaign name or --out, or delete them");
    }

    /** SHA-256 over the files on the suite's classpath, so that a changed measurement code is a different definition. */
    private static String suiteDigest() throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            final Path path = Path.of(entry);
            if (!Files.exists(path))
                continue;
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                        digest.update(path.relativize(f).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(f));
                    }
                }
            } else {
                digest.update(path.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
        }
        return hex(digest.digest());
    }

    private boolean execute(List<Plan.Run> plan) throws Exception {
        int done = 0;
        int notOk = 0;
        for (Plan.Run run : plan) {
            done++;
            final Path file = runFile(run);
            if (Files.exists(file) && "ok".equals(Output.JSON.readTree(file.toFile()).path("status").asText())) {
                System.out.printf("[%d/%d] %s: done earlier%n", done, plan.size(), run.id);
                continue;
            }
            deleteRecursively(home());
            final String shortage = diskShortage(run);
            if (shortage != null) {
                System.out.printf("Stopped before %s: %s; free some space and run the campaign again to resume%n", run.id, shortage);
                return false;
            }
            System.out.printf("[%d/%d] %s%n", done, plan.size(), run.id);
            final ObjectNode result = execute(run);
            writeAtomically(file, result);
            Files.writeString(out.resolve("runs.jsonl"), Output.JSON_LINE.writeValueAsString(result) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            final String status = result.path("status").asText();
            if (!"ok".equals(status))
                notOk++;
            System.out.printf("    %s in %d s%s%n", status, result.path("durationMillis").asLong() / 1000,
                    result.hasNonNull("error") ? ": " + result.get("error").asText() : "");
        }
        if (notOk > 0)
            System.out.printf("%d of %d runs are not ok; rerun the campaign to retry them%n", notOk, plan.size());
        return notOk == 0;
    }

    private ObjectNode execute(Plan.Run run) throws Exception {
        final ObjectNode result = run.toJson();
        result.put("campaign", campaign.path("campaign").asText());
        result.put("definitionSha256", definitionHash(run));
        result.put("startedAt", Instant.now().toString());
        final long start = System.currentTimeMillis();

        final JsonNode server = server(run.server);
        final JsonNode configuration = serverConfiguration(run);
        final Path logs = Files.createDirectories(out.resolve("logs").resolve(run.id));
        final Path home = Files.createDirectories(home());
        final ArrayNode commands = result.putArray("clientCommands");
        final String stream = "perftests_" + result.get("definitionSha256").asText().substring(0, 12) + "_"
                + Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(RANDOM.nextInt());

        try (Server tb = Server.start(server, configuration, home, logs.resolve("server.log"), stream)) {
            result.set("serverStart", tb.describe());
            result.put("isolated", tb.isolated());
            final Map<String, String> p = run.params;
            final ObjectNode metrics = result.putObject("metrics");
            switch (run.type) {
                case "throughput":
                    throughput(tb, stream, p, logs, metrics, commands);
                    break;
                case "latency":
                    single("latency", tb, stream, p, logs, metrics, commands, Plan.number(p, "warmupMessages", LatencyCommand.DEFAULT_WARMUP_MESSAGES) / 10_000
                            + Plan.number(p, "warmupSeconds", LatencyCommand.DEFAULT_WARMUP_SECONDS) + Plan.number(p, "measureSeconds", LatencyCommand.DEFAULT_MEASURE_SECONDS) + 2 * Plan.number(p, "drainSeconds", 10) + 120);
                    break;
                case "correctness":
                    single("verify", tb, stream, p, logs, metrics, commands, Plan.number(p, "clientTimeoutSeconds", 900));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown suite type " + run.type);
            }
            final String invalid = invalidReason(run.type, metrics);
            result.put("status", invalid == null ? "ok" : "invalid");
            if (invalid != null)
                result.put("error", invalid);
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", e.toString());
        }
        result.put("durationMillis", System.currentTimeMillis() - start);
        // The data of a run that is not ok stays for a look until the next run
        if ("ok".equals(result.path("status").asText()))
            deleteRecursively(home);
        return result;
    }

    private Path home() {
        return out.resolve("work").resolve("home");
    }

    /** Why the next run may fill the disk, or {@code null}. */
    private String diskShortage(Plan.Run run) throws IOException {
        if (server(run.server).hasNonNull("url"))
            return null;
        final Path work = Files.createDirectories(out.resolve("work"));
        final long need = diskBytes(run.type, run.params);
        final long free = Files.getFileStore(work).getUsableSpace();
        if (free >= need)
            return null;
        return String.format("it may write %.1f GB, %.1f GB are free in %s", need / 1e9, free / 1e9, work);
    }

    /** Rough upper bound of what a run writes into the server home, plus a reserve for logs and the system. */
    static long diskBytes(String type, Map<String, String> p) {
        final long messages;
        switch (type) {
            case "latency":
                messages = Math.min(Long.MAX_VALUE - LatencyCommand.warmupCount(p), LatencyCommand.measureCount(p)) + LatencyCommand.warmupCount(p);
                break;
            case "throughput":
                messages = Plan.times(MAX_THROUGHPUT, Plan.number(p, "warmupSeconds", 5) + Plan.number(p, "measureSeconds", 10));
                break;
            default:
                messages = Plan.number(p, "total", 0);
        }
        final long bytes = Plan.times(messages, Plan.number(p, "payloadBytes", 0) + MESSAGE_OVERHEAD_BYTES);
        return bytes > Long.MAX_VALUE - DISK_RESERVE_BYTES ? Long.MAX_VALUE : bytes + DISK_RESERVE_BYTES;
    }

    /** Messages per second a writer is assumed to reach, for the disk estimate. */
    private static final long MAX_THROUGHPUT = 1_000_000;
    private static final long MESSAGE_OVERHEAD_BYTES = 64;
    private static final long DISK_RESERVE_BYTES = 1L << 30;

    /** Why the measurements of a completed run must not be used, or {@code null}. */
    static String invalidReason(String type, JsonNode metrics) {
        switch (type) {
            case "latency":
                if (metrics.path("lost").asLong() != 0)
                    return metrics.path("lost").asLong() + " measured messages were not received"
                            + (metrics.has("error") ? " (" + metrics.get("error").asText() + ")" : "");
                if (metrics.path("sequenceViolations").asLong() != 0)
                    return metrics.path("sequenceViolations").asLong() + " sequence violations: " + metrics.path("firstViolation").asText();
                if (metrics.path("negativeLatencies").asLong() != 0)
                    return metrics.path("negativeLatencies").asLong() + " negative latencies";
                if (metrics.path("extraReadersBehind").asLong() != 0)
                    return metrics.path("extraReadersBehind").asLong() + " other readers did not receive every message in order"
                            + (metrics.has("extraReaderErrors") ? ": " + metrics.get("extraReaderErrors").asText() : "");
                return null;
            case "correctness":
                return metrics.path("pass").asBoolean(false) ? null : "Readers did not receive every message in order";
            case "throughput": {
                final JsonNode readers = metrics.path("readers");
                if (readers.isMissingNode())
                    return null;
                if (readers.path("errors").size() > 0)
                    return "Reader errors: " + readers.path("errors");
                if (!readers.path("caughtUp").asBoolean(false))
                    return "Readers did not receive exactly " + readers.path("expected").asLong() + " messages: " + readers.path("received");
                return null;
            }
            default:
                return "Unknown suite type " + type;
        }
    }

    private void throughput(Server tb, String stream, Map<String, String> p, Path logs,
                            ObjectNode metrics, ArrayNode commands) throws Exception {
        final int readers = Integer.parseInt(p.getOrDefault("readers", "0"));
        final long writerTimeout = Plan.number(p, "warmupSeconds", 5) + Plan.number(p, "measureSeconds", 10) + 120;
        final long drain = Plan.number(p, "drainSeconds", 30);
        ClientProcess readersProcess = null;
        try {
            if (readers > 0) {
                final List<String> cmd = client("readers", tb, stream, p);
                commands.add(Output.JSON.valueToTree(cmd));
                readersProcess = ClientProcess.start(cmd, logs.resolve("readers.log"));
                readersProcess.awaitReady(120);
            }
            final List<String> writerCmd = client("writer", tb, stream, p);
            writerCmd.add("--createStream=" + (readers == 0));
            commands.add(Output.JSON.valueToTree(writerCmd));
            final JsonNode writer;
            try (ClientProcess process = ClientProcess.start(writerCmd, logs.resolve("writer.log"))) {
                writer = process.awaitResult(writerTimeout);
            }
            metrics.set("writer", writer);
            if (readersProcess != null) {
                readersProcess.send("EXPECT " + writer.path("messages").asLong() + " " + drain);
                metrics.set("readers", readersProcess.awaitResult(drain + 60));
            }
        } finally {
            if (readersProcess != null)
                readersProcess.close();
        }
    }

    private void single(String command, Server tb, String stream, Map<String, String> p, Path logs,
                        ObjectNode metrics, ArrayNode commands, long timeoutSeconds) throws Exception {
        final List<String> cmd = client(command, tb, stream, p);
        commands.add(Output.JSON.valueToTree(cmd));
        try (ClientProcess client = ClientProcess.start(cmd, logs.resolve(command + ".log"))) {
            metrics.setAll((ObjectNode) client.awaitResult(timeoutSeconds));
        }
    }

    private List<String> client(String command, Server tb, String stream, Map<String, String> params) {
        final JsonNode client = campaign.path("clients").path(params.get(Profiles.CLIENT_CONFIGURATION));
        final List<String> cmd = new ArrayList<>();
        client.path("prefix").forEach(x -> cmd.add(x.asText()));
        cmd.add(client.path("java").asText(defaultJava()));
        if (client.has("jvmArgs"))
            client.path("jvmArgs").forEach(a -> cmd.add(a.asText()));
        else
            cmd.addAll(List.of("-Xmx2g", "-Xms2g"));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        cmd.add(command);
        cmd.add("--url=" + tb.url());
        cmd.add("--stream=" + stream);
        params.forEach((k, v) -> {
            if (!k.equals(Profiles.SERVER_CONFIGURATION) && !k.equals(Profiles.CLIENT_CONFIGURATION))
                cmd.add("--" + k + "=" + v);
        });
        return cmd;
    }

    private Path runFile(Plan.Run run) {
        return out.resolve("runs").resolve(run.id + ".json");
    }

    /** Everything a run's result depends on: its parameters, the server as built and its configuration, the client's. */
    private String definitionHash(Plan.Run run) throws Exception {
        final ObjectNode definition = run.toJson();
        final ObjectNode server = server(run.server).deepCopy();
        server.remove("configurations");
        definition.set("server", server);
        definition.set("serverConfiguration", serverConfiguration(run));
        definition.set("clientConfiguration", campaign.path("clients").path(run.params.get(Profiles.CLIENT_CONFIGURATION)));
        definition.set("suiteSha256", campaign.path("suiteSha256"));
        return hex(MessageDigest.getInstance("SHA-256").digest(Output.JSON_LINE.writeValueAsBytes(definition)));
    }

    private JsonNode serverConfiguration(Plan.Run run) {
        final JsonNode c = server(run.server).path("configurations").path(run.params.get(Profiles.SERVER_CONFIGURATION));
        if (c.isMissingNode())
            throw new IllegalArgumentException("Server " + run.server + " has no configuration " + run.params.get(Profiles.SERVER_CONFIGURATION));
        return c;
    }

    private JsonNode server(String id) {
        for (JsonNode s : campaign.path("servers")) {
            if (id.equals(s.path("id").asText()))
                return s;
        }
        throw new IllegalArgumentException("Unknown server " + id);
    }

    /** Replaces every {@code ${NAME}} with the environment variable; an unset variable is an error. */
    static String substituteEnvironment(String text, Map<String, String> env) {
        final Matcher m = VARIABLE.matcher(text);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            final String value = env.get(m.group(1));
            if (value == null)
                throw new IllegalArgumentException("Environment variable " + m.group(1) + " is not set");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String defaultJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static ObjectNode environment(JsonNode campaign) throws Exception {
        final ObjectNode env = Output.JSON.createObjectNode();
        env.put("capturedAt", Instant.now().toString());
        env.put("osName", System.getProperty("os.name"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("processors", Runtime.getRuntime().availableProcessors());
        env.put("runnerJava", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        final Path cwd = Path.of("").toAbsolutePath();
        env.put("suiteCommit", tryExec(cwd, "git", "rev-parse", "HEAD"));
        final String status = tryExec(cwd, "git", "status", "--porcelain");
        if (status != null)
            env.put("suiteUncommittedChanges", !status.isEmpty());
        final java.util.Set<String> serverJavas = new java.util.TreeSet<>();
        for (JsonNode v : campaign.path("servers")) {
            if (!v.hasNonNull("image") && !v.hasNonNull("url"))
                v.path("configurations").forEach(c -> serverJavas.add(c.path("java").asText(defaultJava())));
        }
        final java.util.Set<String> clientJavas = new java.util.TreeSet<>();
        campaign.path("clients").forEach(c -> clientJavas.add(c.path("java").asText(defaultJava())));
        final ObjectNode servers = env.putObject("serverJavas");
        for (String java : serverJavas)
            servers.put(java, tryExec(cwd, java, "-version"));
        final ObjectNode clients = env.putObject("clientJavas");
        for (String java : clientJavas)
            clients.put(java, tryExec(cwd, java, "-version"));
        final ArrayNode described = env.putArray("servers");
        for (JsonNode v : campaign.path("servers")) {
            final ObjectNode node = described.addObject();
            node.put("id", v.path("id").asText());
            if (v.hasNonNull("url")) {
                node.put("url", v.get("url").asText());
                continue;
            }
            if (v.hasNonNull("image")) {
                node.put("image", v.get("image").asText());
                node.put("imageId", v.path("imageId").asText());
                continue;
            }
            final Path dist = Path.of(v.path("dist").asText());
            node.put("serverJarSha256", sha256(ServerProcess.serverJar(dist.resolve("lib"))));
            if (v.hasNonNull("overlaySha256"))
                node.set("overlaySha256", v.get("overlaySha256"));
            if (v.has("built"))
                node.set("built", v.get("built"));
        }
        return env;
    }

    private static String tryExec(Path dir, String... command) {
        try {
            return ServerBuild.exec(dir, command);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path file) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** SHA-256 over the relative names and contents of every file under {@code dir}. */
    static String treeSha256(Path dir) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                digest.update((dir.relativize(f) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(f));
            }
        }
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static void writeAtomically(Path file, JsonNode node) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Output.JSON.writeValue(tmp.toFile(), node);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                Files.delete(f);
        }
    }
}
