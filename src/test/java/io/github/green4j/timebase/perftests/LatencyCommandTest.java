package io.github.green4j.timebase.perftests;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LatencyCommandTest {

    private static PerfMessage message(long sequence, int symbols) {
        final PerfMessage m = new PerfMessage();
        m.sequence = sequence;
        m.symbolSequence = sequence / symbols;
        return m;
    }

    @Test
    void symbolsMayInterleaveInAnyOrder() {
        final LatencyCommand.SymbolOrder order = new LatencyCommand.SymbolOrder(3);
        for (long sequence : new long[] {2, 0, 1, 5, 3, 4})
            assertNull(order.check(message(sequence, 3)), "Sequence " + sequence + " was rejected");
    }

    @Test
    void oneSymbolOutOfOrderIsAViolation() {
        final LatencyCommand.SymbolOrder order = new LatencyCommand.SymbolOrder(2);
        assertNull(order.check(message(0, 2)));
        assertNotNull(order.check(message(4, 2)), "A skipped message of a symbol was accepted");
    }

    @Test
    void duplicateIsAViolation() {
        final LatencyCommand.SymbolOrder order = new LatencyCommand.SymbolOrder(1);
        assertNull(order.check(message(0, 1)));
        assertNotNull(order.check(message(0, 1)), "A duplicate was accepted");
    }

    @Test
    void orderRecoversAfterAViolation() {
        final LatencyCommand.SymbolOrder order = new LatencyCommand.SymbolOrder(1);
        assertNull(order.check(message(0, 1)));
        assertNotNull(order.check(message(2, 1)), "A skipped message was accepted");
        assertNull(order.check(message(3, 1)), "The message after a skip was rejected");
    }

    @Test
    void sequenceOfAnotherSymbolIsAViolation() {
        final LatencyCommand.SymbolOrder order = new LatencyCommand.SymbolOrder(2);
        final PerfMessage m = message(1, 2);
        m.symbolSequence = 1;
        assertNotNull(order.check(m), "A per-symbol sequence that does not match the global one was accepted");
    }

    @Test
    void measuredMessagesStayWithinTheDurationBounds() {
        assertEquals(20_000, LatencyCommand.measureCount(1_000, 20, 600_000), "A low rate measured longer than measureSeconds");
        assertEquals(600_000, LatencyCommand.measureCount(200_000, 20, 300_000), "A high rate measured less than 3 s");
        assertEquals(400_000, LatencyCommand.measureCount(200_000, 2, 10), "A short measureSeconds did not bound the minimum");
        assertEquals(300_000, LatencyCommand.measureCount(50_000, 20, 300_000), "measureMessages within the bounds was not taken");
        assertEquals(0, LatencyCommand.measureCount(0, 20, 100), "A zero rate measured messages");
        assertEquals(Long.MAX_VALUE, LatencyCommand.measureCount(Long.MAX_VALUE / 2, 20, Long.MAX_VALUE), "An overflow wrapped around");
        assertThrows(IllegalArgumentException.class, () -> LatencyCommand.measureCount(-1, 20, 100), "A negative rate was accepted");
    }

    @Test
    void warmupIsTheFastPartPlusTheRatePart() {
        assertEquals(2_005, LatencyCommand.warmupCount(Map.of("rate", "1000", "warmupSeconds", "2", "warmupMessages", "5")));
        assertEquals(Long.MAX_VALUE, LatencyCommand.warmupCount(Map.of("rate", String.valueOf(Long.MAX_VALUE / 2), "warmupSeconds", "3")),
                "An overflow wrapped around");
    }
}
