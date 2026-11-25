package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A TimeBase server started from a distribution directory, optionally with an overlay jar placed first on the
 * classpath so that its classes replace the stock ones.
 */
final class ServerProcess implements Server {

    private static final String MAIN = "com.epam.deltix.qsrv.comm.cat.TBServerCmd";

    private final Process process;
    final List<String> command;
    final int port;

    private ServerProcess(Process process, List<String> command, int port) {
        this.process = process;
        this.command = command;
        this.port = port;
    }

    static List<String> command(JsonNode server, JsonNode configuration, Path home, int port) throws IOException {
        final Path dist = Path.of(required(server, "dist"));
        final Path lib = dist.resolve("lib");
        final List<String> classpath = new ArrayList<>();
        if (server.hasNonNull("overlay"))
            classpath.add(Path.of(server.get("overlay").asText()).toAbsolutePath().toString());
        classpath.add(serverJar(lib).toString());
        classpath.add(lib.resolve("custom").resolve("*").toString());

        final List<String> cmd = new ArrayList<>();
        configuration.path("prefix").forEach(p -> cmd.add(p.asText()));
        cmd.add(configuration.path("java").asText(Runner.defaultJava()));
        configuration.path("jvmArgs").forEach(a -> cmd.add(a.asText()));
        cmd.add("-Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true");
        cmd.add("-DTimeBase.webPort=" + configuration.path("webPort").asInt(port + 10));
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, classpath));
        cmd.add(MAIN);
        cmd.add("-home");
        cmd.add(home.toAbsolutePath().toString());
        cmd.add("-port");
        cmd.add(Integer.toString(port));
        return cmd;
    }

    static Path serverJar(Path lib) throws IOException {
        try (Stream<Path> files = Files.list(lib)) {
            final List<Path> jars = files.filter(p -> p.getFileName().toString().matches("timebase-server-.*\\.jar"))
                    .collect(Collectors.toList());
            if (jars.size() != 1)
                throw new IllegalArgumentException("Expected exactly one timebase-server-*.jar in " + lib + ", found " + jars);
            return jars.get(0);
        }
    }

    static ServerProcess start(JsonNode server, JsonNode configuration, Path home, Path log, int port) throws Exception {
        final List<String> cmd = command(server, configuration, home, port);
        final ProcessBuilder builder = new ProcessBuilder(cmd)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("DELTIX_HOME", Path.of(required(server, "dist")).toAbsolutePath().toString());
        final Process process = builder.start();
        final ServerProcess result = new ServerProcess(process, cmd, port);
        try {
            Server.awaitReady(result.url(), port, process::isAlive, TimeUnit.SECONDS.toMillis(configuration.path("startTimeoutSeconds").asInt(60)));
        } catch (Exception e) {
            result.close();
            throw e;
        }
        return result;
    }

    @Override
    public String url() {
        return "dxtick://localhost:" + port;
    }

    @Override
    public boolean isolated() {
        return true;
    }

    @Override
    public JsonNode describe() {
        return Output.JSON.valueToTree(command);
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        final long deadline = System.currentTimeMillis() + 30_000;
        while (Server.listening(port) && System.currentTimeMillis() < deadline)
            Thread.sleep(250);
    }

    private static String required(JsonNode node, String field) {
        if (!node.hasNonNull(field))
            throw new IllegalArgumentException(field + " is required in " + node);
        return node.get(field).asText();
    }
}
