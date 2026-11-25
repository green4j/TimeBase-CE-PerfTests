package io.github.green4j.timebase.perftests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.HdrHistogram.Histogram;

/**
 * Protocol between a client process and the runner: lines on stdout starting with {@link #READY} or {@link #RESULT}.
 */
final class Output {

    static final String READY = "READY";
    static final String RESULT = "RESULT ";

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final ObjectMapper JSON_LINE = new ObjectMapper();

    private Output() {
    }

    static void ready() {
        System.out.println(READY);
        System.out.flush();
    }

    static void result(ObjectNode result) {
        try {
            System.out.println(RESULT + JSON_LINE.writeValueAsString(result));
            System.out.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write the result", e);
        }
    }

    /** Percentiles of a nanosecond histogram, in microseconds. */
    static ObjectNode latency(Histogram h) {
        final ObjectNode node = JSON.createObjectNode();
        node.put("count", h.getTotalCount());
        node.put("p50", h.getValueAtPercentile(50) / 1e3);
        node.put("p90", h.getValueAtPercentile(90) / 1e3);
        node.put("p99", h.getValueAtPercentile(99) / 1e3);
        node.put("p99_9", h.getValueAtPercentile(99.9) / 1e3);
        node.put("p99_99", h.getValueAtPercentile(99.99) / 1e3);
        node.put("max", h.getMaxValue() / 1e3);
        node.put("mean", h.getMean() / 1e3);
        return node;
    }
}
