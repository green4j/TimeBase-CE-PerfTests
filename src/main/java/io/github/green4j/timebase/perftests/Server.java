package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * The server one run talks to. {@link #start} picks the kind from the server: {@code url} for a server that is already
 * running, {@code image} for a Docker image, otherwise a distribution directory started as a local process.
 */
interface Server extends AutoCloseable {

    String url();

    /** Whether the run had the server to itself from a clean start. */
    boolean isolated();

    /** How the server was started, for the run file. */
    JsonNode describe();

    @Override
    void close() throws Exception;

    static Server start(JsonNode server, JsonNode configuration, Path home, Path log, String stream) throws Exception {
        if (server.hasNonNull("url"))
            return ExternalServer.connect(server.get("url").asText(), stream);
        if (server.hasNonNull("image"))
            return DockerServer.start(server, configuration, log);
        return ServerProcess.start(server, configuration, home, log, configuration.path("port").asInt(18011));
    }

    /**
     * Waits until a TimeBase client can open {@code url} while {@code alive} holds. With a positive {@code port}, a
     * client is only tried once the port accepts connections, since opening a dead URL retries for seconds.
     */
    static void awaitReady(String url, int port, BooleanSupplier alive, long timeoutMillis) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!alive.getAsBoolean())
                throw new IllegalStateException("Server stopped while starting");
            if (port > 0 && !listening(port)) {
                Thread.sleep(250);
                continue;
            }
            try {
                final DXTickDB db = Tb.open(url, true);
                db.close();
                // The web monitor starts after the TimeBase port and stops the server when it fails
                Thread.sleep(1000);
                if (!alive.getAsBoolean())
                    throw new IllegalStateException("Server stopped right after start");
                return;
            } catch (RuntimeException e) {
                last = e;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Server " + url + " did not become ready in " + timeoutMillis + " ms", last);
    }

    static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
