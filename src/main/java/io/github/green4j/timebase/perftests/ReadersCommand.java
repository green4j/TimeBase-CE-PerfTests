package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickCursor;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Live readers for the throughput suite, each with its own connection and thread, reading the stream from its start.
 * Creates the stream and prints {@code READY} once every cursor is live. Then reads {@code EXPECT <messages>
 * <drainSeconds>} from stdin, waits until every reader has received that many messages or the drain expires, and
 * prints the result.
 */
final class ReadersCommand {

    /** Counters of different readers are this many longs apart, so that they never share a cache line. */
    private static final int STRIDE = 16;

    private ReadersCommand() {
    }

    static void run(Args args) throws Exception {
        final String url = args.string("url");
        final String key = args.string("stream");
        final int readers = args.integer("readers");
        final boolean raw = args.bool("raw", true);
        final ChannelPerformance readerMode = Tb.readerMode(args);

        final DXTickDB admin = Tb.open(url, false);
        Tb.createStream(admin, key);

        final AtomicLongArray received = new AtomicLongArray(readers * STRIDE);
        final AtomicReferenceArray<String> errors = new AtomicReferenceArray<>(readers);
        final CountDownLatch live = new CountDownLatch(readers);
        for (int r = 0; r < readers; r++) {
            final int reader = r;
            final Thread thread = new Thread(() -> {
                final DXTickDB db = Tb.open(url, true);
                try (TickCursor cursor = Tb.liveCursor(db, db.getStream(key), raw, readerMode)) {
                    long n = 0;
                    while (cursor.next()) {
                        if (Tb.isRealTimeStart(cursor.getMessage()))
                            live.countDown();
                        else
                            received.lazySet(reader * STRIDE, ++n);
                    }
                    errors.set(reader, "Cursor ended");
                } catch (RuntimeException e) {
                    errors.compareAndSet(reader, null, e.toString());
                }
            }, "reader-" + r);
            thread.setDaemon(true);
            thread.start();
        }
        if (!live.await(60, TimeUnit.SECONDS))
            throw new IllegalStateException("Readers did not go live in 60 s");
        Output.ready();

        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final String line = in.readLine();
        if (line == null || !line.startsWith("EXPECT "))
            throw new IllegalStateException("Expected EXPECT <messages> <drainSeconds>, got " + line);
        final String[] parts = line.split(" ");
        final long expected = Long.parseLong(parts[1]);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(parts[2]));
        while (System.nanoTime() < deadline && !allReached(received, expected))
            Thread.sleep(50);

        final ObjectNode result = Output.JSON.createObjectNode();
        final ArrayNode perReader = result.putArray("received");
        final ArrayNode errorList = result.putArray("errors");
        long min = Long.MAX_VALUE;
        boolean exact = true;
        for (int r = 0; r < readers; r++) {
            final long n = received.get(r * STRIDE);
            perReader.add(n);
            min = Math.min(min, n);
            exact &= n == expected;
            if (errors.get(r) != null)
                errorList.add("reader-" + r + ": " + errors.get(r));
        }
        result.put("readers", readers);
        result.put("expected", expected);
        result.put("minReceived", min);
        result.put("caughtUp", exact);
        Output.result(result);
        System.exit(0);
    }

    private static boolean allReached(AtomicLongArray received, long expected) {
        for (int r = 0; r < received.length(); r += STRIDE) {
            if (received.get(r) < expected)
                return false;
        }
        return true;
    }
}
