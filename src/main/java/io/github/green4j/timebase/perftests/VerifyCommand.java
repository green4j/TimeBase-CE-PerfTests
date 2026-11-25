package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickCursor;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickLoader;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Correctness under live reading: message {@code i} has timestamp {@code base + i} ns, so every reader must see
 * exactly 0, 1, 2, ... with the expected symbol, per-symbol sequence and timestamp. The writer alternates bursts
 * and pauses so that readers keep falling asleep and waking up; one reader is slow; one joins at 30 % of the
 * stream, and the writer waits until it has read the history and gone live.
 */
final class VerifyCommand {

    private VerifyCommand() {
    }

    private static final class Reader extends Thread {
        private final String url;
        private final String key;
        private final String[] symbols;
        private final long baseNanos;
        private final boolean slow;
        private final ChannelPerformance mode;
        private final CountDownLatch live;
        volatile long received;
        volatile long violations;
        volatile String firstViolation;
        volatile String error;

        Reader(String name, String url, String key, String[] symbols, long baseNanos, boolean slow, ChannelPerformance mode,
               CountDownLatch live) {
            super(name);
            this.url = url;
            this.key = key;
            this.symbols = symbols;
            this.baseNanos = baseNanos;
            this.slow = slow;
            this.mode = mode;
            this.live = live;
            setDaemon(true);
        }

        @Override
        public void run() {
            final DXTickDB db = Tb.open(url, true);
            final DXTickStream stream = db.getStream(key);
            long expected = 0;
            try (TickCursor cursor = Tb.liveCursor(db, stream, false, mode)) {
                while (cursor.next()) {
                    if (Tb.isRealTimeStart(cursor.getMessage())) {
                        live.countDown();
                        continue;
                    }
                    final PerfMessage m = (PerfMessage) cursor.getMessage();
                    final int s = (int) (expected % symbols.length);
                    if (m.sequence != expected
                            || m.symbolSequence != expected / symbols.length
                            || !symbols[s].contentEquals(m.getSymbol())
                            || m.getNanoTime() != baseNanos + expected) {
                        if (violations++ == 0)
                            firstViolation = "Expected " + expected + "/" + symbols[s] + "@" + (baseNanos + expected)
                                    + ", got " + m.sequence + "/" + m.getSymbol() + "@" + m.getNanoTime();
                        expected = m.sequence;
                    }
                    expected++;
                    received = received + 1;
                    if (slow && expected % 2000 == 0)
                        LockSupport.parkNanos(2_000_000);
                }
                error = "Cursor ended";
            } catch (RuntimeException e) {
                error = e.toString();
            }
        }
    }

    static void run(Args args) throws Exception {
        final String url = args.string("url");
        final String key = args.string("stream");
        final ChannelPerformance performance = args.enumValue("mode", ChannelPerformance.class, ChannelPerformance.MIN_CPU_USAGE);
        final ChannelPerformance readerMode = Tb.readerMode(args);
        final long total = args.longValue("total", 1_000_000);
        final int live = args.integer("liveReaders", 8);
        final int burst = args.integer("burst", 2000);
        final long pauseNanos = args.longValue("pauseMillis", 2) * 1_000_000L;
        final String[] symbols = Tb.symbols(args.integer("symbols", 100));
        final long timeoutMillis = args.longValue("timeoutSeconds", 60) * 1000L;

        final DXTickDB db = Tb.open(url, false);
        final DXTickStream stream = Tb.createStream(db, key);
        final long baseNanos = System.currentTimeMillis() * 1_000_000L;

        final CountDownLatch initialLive = new CountDownLatch(live + 1);
        final CountDownLatch lateLive = new CountDownLatch(1);
        final List<Reader> readers = new ArrayList<>();
        for (int i = 0; i < live; i++)
            readers.add(new Reader("live-" + i, url, key, symbols, baseNanos, false, readerMode, initialLive));
        readers.add(new Reader("slow", url, key, symbols, baseNanos, true, readerMode, initialLive));
        final Reader late = new Reader("late", url, key, symbols, baseNanos, false, readerMode, lateLive);
        for (Reader r : readers)
            r.start();
        readers.add(late);
        if (!initialLive.await(60, TimeUnit.SECONDS))
            throw new IllegalStateException("Readers did not go live in 60 s");

        final PerfMessage msg = new PerfMessage();
        final long lateAt = total * 3 / 10;
        final long t0 = System.nanoTime();
        try (TickLoader<InstrumentMessage> loader = Tb.loader(stream, performance)) {
            for (long i = 0; i < total; i++) {
                if (i == lateAt) {
                    loader.flush();
                    late.start();
                    if (!lateLive.await(timeoutMillis, TimeUnit.MILLISECONDS))
                        throw new IllegalStateException("The late reader did not go live in " + timeoutMillis + " ms");
                }
                final int s = (int) (i % symbols.length);
                msg.setSymbol(symbols[s]);
                msg.setNanoTime(baseNanos + i);
                msg.sequence = i;
                msg.symbolSequence = i / symbols.length;
                loader.send(msg);
                if (burst > 0 && (i + 1) % burst == 0)
                    LockSupport.parkNanos(pauseNanos);
            }
        }
        final double writeSeconds = (System.nanoTime() - t0) / 1e9;

        final long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean done;
        do {
            done = true;
            for (Reader r : readers) {
                if (r.received < total)
                    done = false;
            }
            if (!done)
                Thread.sleep(200);
        } while (!done && System.currentTimeMillis() < deadline);

        boolean pass = true;
        final ObjectNode result = Output.JSON.createObjectNode();
        final ArrayNode perReader = result.putArray("readers");
        for (Reader r : readers) {
            final boolean ok = r.received == total && r.violations == 0;
            pass &= ok;
            final ObjectNode node = perReader.addObject();
            node.put("name", r.getName());
            node.put("received", r.received);
            node.put("violations", r.violations);
            if (r.firstViolation != null)
                node.put("firstViolation", r.firstViolation);
            if (!ok && r.error != null)
                node.put("error", r.error);
            node.put("ok", ok);
        }
        result.put("total", total);
        result.put("writeRate", total / writeSeconds);
        result.put("pass", pass);
        Output.result(result);
        db.close();
        System.exit(0);
    }
}
