package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunnerTest {

    private static JsonNode json(String text) throws Exception {
        return Output.JSON.readTree(text);
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
