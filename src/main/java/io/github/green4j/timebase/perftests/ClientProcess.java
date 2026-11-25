package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A client command in its own JVM. Its output goes to a log; {@code READY} and {@code RESULT} lines are picked up.
 */
final class ClientProcess implements AutoCloseable {

    private final Process process;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread pump;
    private volatile JsonNode result;
    final List<String> command;

    private ClientProcess(Process process, List<String> command, Path log) throws IOException {
        this.process = process;
        this.command = command;
        final PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8), true);
        pump = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.println(line);
                    if (line.equals(Output.READY))
                        ready.countDown();
                    else if (line.startsWith(Output.RESULT))
                        result = Output.JSON.readTree(line.substring(Output.RESULT.length()));
                }
            } catch (IOException e) {
                out.println("Output pump failed: " + e);
            } finally {
                out.close();
            }
        }, "pump-" + log.getFileName());
        pump.setDaemon(true);
        pump.start();
    }

    static ClientProcess start(List<String> command, Path log) throws IOException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new ClientProcess(process, command, log);
    }

    void awaitReady(long timeoutSeconds) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!ready.await(200, TimeUnit.MILLISECONDS)) {
            if (!process.isAlive())
                throw new IllegalStateException("Client exited with code " + process.exitValue() + " before READY: " + command);
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("Client did not report READY in " + timeoutSeconds + " s: " + command);
        }
    }

    void send(String line) throws IOException {
        final OutputStream in = process.getOutputStream();
        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /** Waits for the exit and returns the result; fails on timeout, a non-zero exit or a missing result. */
    JsonNode awaitResult(long timeoutSeconds) throws InterruptedException {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            throw new IllegalStateException("Client did not finish in " + timeoutSeconds + " s: " + command);
        pump.join(5000);
        if (process.exitValue() != 0)
            throw new IllegalStateException("Client exited with code " + process.exitValue() + ": " + command);
        if (result == null)
            throw new IllegalStateException("Client printed no result: " + command);
        return result;
    }

    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
