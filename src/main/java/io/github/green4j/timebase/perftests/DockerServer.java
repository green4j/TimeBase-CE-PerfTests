package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A fresh container of a TimeBase server image per run: {@code "image": "finos/timebase-ce-server:6.2.34"}. The
 * image's {@code tdbserver.sh} reads JVM options from {@code JAVA_OPTS}; its port, 8011 by default, is published on
 * the campaign's server port. The container output is saved to the server log when the run ends.
 */
final class DockerServer implements Server {

    private static final Path CWD = Path.of("").toAbsolutePath();

    private final String name;
    private final int port;
    private final Path log;
    final List<String> command;

    private DockerServer(String name, int port, Path log, List<String> command) {
        this.name = name;
        this.port = port;
        this.log = log;
        this.command = command;
    }

    /** The image id the tag currently points to, pulling the image if it is not present. */
    static String imageId(String image) throws Exception {
        try {
            return ServerBuild.exec(CWD, "docker", "image", "inspect", "--format", "{{.Id}}", image);
        } catch (IllegalStateException e) {
            ServerBuild.exec(CWD, "docker", "pull", "-q", image);
            return ServerBuild.exec(CWD, "docker", "image", "inspect", "--format", "{{.Id}}", image);
        }
    }

    static DockerServer start(JsonNode server, JsonNode configuration, Path log) throws Exception {
        final int port = configuration.path("port").asInt(18011);
        final String name = "perftests-" + port;
        removeContainer(name);

        final List<String> javaOpts = new ArrayList<>();
        configuration.path("jvmArgs").forEach(a -> javaOpts.add(a.asText()));
        final List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name,
                "-p", port + ":" + server.path("containerPort").asInt(8011),
                "-e", "JAVA_OPTS=" + String.join(" ", javaOpts)));
        configuration.path("dockerArgs").forEach(a -> cmd.add(a.asText()));
        cmd.add(server.path("imageId").asText(server.get("image").asText()));
        ServerBuild.exec(CWD, cmd.toArray(new String[0]));

        final DockerServer result = new DockerServer(name, port, log, cmd);
        try {
            Server.awaitReady(result.url(), 0, result::running, TimeUnit.SECONDS.toMillis(configuration.path("startTimeoutSeconds").asInt(60)));
        } catch (Exception e) {
            result.close();
            throw e;
        }
        return result;
    }

    private boolean running() {
        try {
            return "true".equals(ServerBuild.exec(CWD, "docker", "inspect", "--format", "{{.State.Running}}", name));
        } catch (Exception e) {
            return false;
        }
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
    public void close() throws Exception {
        try {
            ServerBuild.exec(CWD, "docker", "stop", "-t", "30", name);
        } catch (IllegalStateException e) {
            // Already stopped
        }
        try {
            Files.writeString(log, ServerBuild.exec(CWD, "docker", "logs", name));
        } catch (IllegalStateException e) {
            Files.writeString(log, "Cannot read the container log: " + e.getMessage());
        }
        removeContainer(name);
    }

    private static void removeContainer(String name) throws Exception {
        try {
            ServerBuild.exec(CWD, "docker", "container", "inspect", "--format", "{{.Id}}", name);
        } catch (IllegalStateException e) {
            return; // No such container
        }
        ServerBuild.exec(CWD, "docker", "rm", "-f", name);
    }
}
