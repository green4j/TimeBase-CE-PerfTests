package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    private static JsonNode json(String text) throws Exception {
        return Output.JSON.readTree(text);
    }

    /** A runner with a long child: prints the child's pid and waits to be stopped. */
    public static void main(String[] args) throws Exception {
        Runner.stopChildrenOnExit();
        final Process child = new ProcessBuilder("sleep", "60").start();
        System.out.println(child.pid());
        System.out.flush();
        Thread.sleep(60_000);
    }

    @Test
    void aRunnerStoppedBySignalStopsItsChildren() throws Exception {
        final Process runner = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), RunnerTest.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            final String pid = new BufferedReader(new InputStreamReader(runner.getInputStream(), StandardCharsets.UTF_8)).readLine();
            final ProcessHandle child = ProcessHandle.of(Long.parseLong(pid.trim())).orElseThrow();

            runner.destroy();

            assertTrue(runner.waitFor(30, TimeUnit.SECONDS), "The runner did not exit on SIGTERM");
            child.onExit().completeOnTimeout(child, 10, TimeUnit.SECONDS).get();
            assertFalse(child.isAlive(), "A child outlived the runner stopped by a signal");
        } finally {
            runner.destroyForcibly();
        }
    }

    @Test
    void substitutesEnvironmentVariables() {
        assertEquals("{\"dist\": \"/opt/tb\"}", Runner.substituteEnvironment("{\"dist\": \"${TB_DIST}\"}", Map.of("TB_DIST", "/opt/tb")));
    }

    @Test
    void unsetVariableIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Runner.substituteEnvironment("${NOPE}", Map.of()));
    }

    @Test
    void latencyWithLostMessagesIsInvalid() throws Exception {
        assertNull(Runner.invalidReason("latency", json("{\"lost\": 0, \"sequenceViolations\": 0, \"negativeLatencies\": 0}")),
                "A clean latency run was rejected");
        assertNotNull(Runner.invalidReason("latency", json("{\"lost\": 3}")), "Lost messages were accepted");
        assertNotNull(Runner.invalidReason("latency", json("{\"lost\": 0, \"sequenceViolations\": 1}")), "A reordering was accepted");
        assertNotNull(Runner.invalidReason("latency", json("{\"lost\": 0, \"negativeLatencies\": 1}")), "A negative latency was accepted");
    }

    @Test
    void throughputReadersMustCatchUpWithoutErrors() throws Exception {
        assertNull(Runner.invalidReason("throughput", json("{\"writer\": {}}")), "A run without readers was rejected");
        assertNull(Runner.invalidReason("throughput", json("{\"readers\": {\"caughtUp\": true, \"errors\": []}}")),
                "Readers that caught up were rejected");
        assertNotNull(Runner.invalidReason("throughput", json("{\"readers\": {\"caughtUp\": false, \"errors\": []}}")),
                "Readers that fell behind were accepted");
        assertNotNull(Runner.invalidReason("throughput", json("{\"readers\": {\"caughtUp\": true, \"errors\": [\"boom\"]}}")),
                "A reader error was accepted");
    }

    @Test
    void latencyWithAnExtraReaderBehindIsInvalid() throws Exception {
        assertNotNull(Runner.invalidReason("latency", json("{\"lost\": 0, \"extraReadersBehind\": 1}")),
                "An extra reader that fell behind was accepted");
    }

    @Test
    void diskEstimateGrowsWithTheMessagesWritten() {
        final long reserve = Runner.diskBytes("correctness", Map.of("total", "0"));
        assertEquals(reserve + (200_000L + 1000L + 1000L * 20) * 264, Runner.diskBytes("latency", Map.of("rate", "1000", "payloadBytes", "200")),
                "Wrong latency estimate: the warmups and 20 s at 1 000 msg/s");
        assertEquals(reserve + (200_000L + 200_000L + 600_000L) * 64, Runner.diskBytes("latency", Map.of("rate", "200000")),
                "Wrong latency estimate: the warmups and measureMessages");
        assertEquals(reserve + 1_000_000L * 15 * 64, Runner.diskBytes("throughput", Map.of()), "Wrong throughput estimate");
        assertEquals(reserve + 300_000L * 64, Runner.diskBytes("correctness", Map.of("total", "300000")), "Wrong correctness estimate");
        assertEquals(Long.MAX_VALUE, Runner.diskBytes("latency", Map.of("rate", String.valueOf(Long.MAX_VALUE / 2))),
                "An estimate that overflows passed the check");
    }

    @Test
    void failedCorrectnessIsInvalid() throws Exception {
        assertNull(Runner.invalidReason("correctness", json("{\"pass\": true}")));
        assertNotNull(Runner.invalidReason("correctness", json("{\"pass\": false}")), "A failed correctness run was accepted");
    }
}
