package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickCursor;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickLoader;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.HdrHistogram.Histogram;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop latency: the writer sends bursts on a fixed schedule regardless of how readers keep up and records how
 * long every {@code send()} takes; one measured reader records {@code receive - sent} (transport) and
 * {@code receive - scheduled} (schedule) for every message after the warmup; the other readers only consume. Writer and measured reader share this JVM so that both use the
 * same {@link System#nanoTime()} origin.
 */
final class LatencyCommand {

    private static final long SPIN_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private LatencyCommand() {
    }

    /**
     * Checks that every message arrives once and the messages of each symbol in order. TimeBase orders the messages
     * of different symbols only by timestamp, and the server stamps them with milliseconds.
     */
    static final class SymbolOrder {
        private final long[] next;

        SymbolOrder(int symbols) {
            next = new long[symbols];
        }

        /** Returns {@code null} or what is wrong with the message. */
        String check(PerfMessage m) {
            final int s = (int) (m.sequence % next.length);
            final long expected = next[s];
            next[s] = m.symbolSequence + 1;
            if (m.symbolSequence == expected && m.sequence == expected * next.length + s)
                return null;
            return "Symbol " + s + ": expected " + expected + ", got " + m.symbolSequence + " (sequence " + m.sequence + ")";
        }
    }

    /** Only consumes, but must also see every message exactly once, each symbol in order. */
    private static final class ExtraReader extends Thread {
        final TickCursor cursor;
        private final long total;
        private final CountDownLatch live;
        private final SymbolOrder order;
        volatile long received;
        volatile String error;

        ExtraReader(TickCursor cursor, long total, int symbols, CountDownLatch live, int index) {
            super("extra-reader-" + index);
            this.cursor = cursor;
            this.total = total;
            this.order = new SymbolOrder(symbols);
            this.live = live;
            setDaemon(true);
        }

        @Override
        public void run() {
            long n = 0;
            try {
                while (cursor.next()) {
                    if (Tb.isRealTimeStart(cursor.getMessage())) {
                        live.countDown();
                        continue;
                    }
                    final String violation = order.check((PerfMessage) cursor.getMessage());
                    if (violation != null) {
                        error = violation;
                        return;
                    }
                    received = ++n;
                    if (n == total)
                        return;
                }
                error = "Cursor ended";
            } catch (RuntimeException e) {
                error = e.toString();
            }
        }
    }

    private static final class MeasuredReader extends Thread {
        private final TickCursor cursor;
        private final long warmupCount;
        private final long total;
        private final SymbolOrder order;
        private final CountDownLatch live;
        final Histogram transport = new Histogram(3);
        final Histogram schedule = new Histogram(3);
        volatile long received;
        volatile long seen;
        volatile long sequenceViolations;
        volatile long negativeLatencies;
        volatile String firstViolation;
        volatile String error;

        MeasuredReader(TickCursor cursor, long warmupCount, long total, int symbols, CountDownLatch live) {
            super("measured-reader");
            this.cursor = cursor;
            this.warmupCount = warmupCount;
            this.total = total;
            this.order = new SymbolOrder(symbols);
            this.live = live;
        }

        @Override
        public void run() {
            try {
                while (cursor.next()) {
                    final long now = System.nanoTime();
                    if (Tb.isRealTimeStart(cursor.getMessage())) {
                        live.countDown();
                        continue;
                    }
                    final PerfMessage m = (PerfMessage) cursor.getMessage();
                    final String violation = order.check(m);
                    if (violation != null) {
                        if (sequenceViolations++ == 0)
                            firstViolation = violation;
                    }
                    if (m.sequence >= warmupCount) {
                        final long fromSent = now - m.sentNanos;
                        final long fromScheduled = now - m.scheduledNanos;
                        if (fromSent < 0 || fromScheduled < 0) {
                            negativeLatencies++;
                        } else {
                            transport.recordValue(fromSent);
                            schedule.recordValue(fromScheduled);
                        }
                        received++;
                    }
                    if (++seen == total)
                        return;
                }
                error = "Cursor ended before the last message";
            } catch (RuntimeException e) {
                error = e.toString();
            }
        }
    }

    static final long DEFAULT_WARMUP_MESSAGES = 200_000;
    static final long DEFAULT_WARMUP_SECONDS = 1;
    static final long DEFAULT_MEASURE_SECONDS = 20;
    static final long DEFAULT_MEASURE_MESSAGES = 600_000;
    /** The shortest measurement, so that a high rate still spans pauses of the server and of the machine. */
    static final long MIN_MEASURE_SECONDS = 3;

    /**
     * Messages measured at {@code rate}: {@code measureMessages}, but at most {@code measureSeconds} and at least
     * {@link #MIN_MEASURE_SECONDS} (or {@code measureSeconds} if shorter) worth of them.
     */
    static long measureCount(long rate, long measureSeconds, long measureMessages) {
        final long most = Plan.times(rate, measureSeconds);
        final long least = Plan.times(rate, Math.min(MIN_MEASURE_SECONDS, measureSeconds));
        return Math.max(least, Math.min(measureMessages, most));
    }

    static long measureCount(Map<String, String> p) {
        return measureCount(Plan.number(p, "rate", 200_000), Plan.number(p, "measureSeconds", DEFAULT_MEASURE_SECONDS),
                Plan.number(p, "measureMessages", DEFAULT_MEASURE_MESSAGES));
    }

    /** Messages sent before the measured ones: the warmup at full speed and {@code warmupSeconds} at the rate. */
    static long warmupCount(Map<String, String> p) {
        final long paced = Plan.times(Plan.number(p, "rate", 200_000), Plan.number(p, "warmupSeconds", DEFAULT_WARMUP_SECONDS));
        return Math.min(Long.MAX_VALUE - paced, Plan.number(p, "warmupMessages", DEFAULT_WARMUP_MESSAGES)) + paced;
    }

    private static void awaitReaders(MeasuredReader reader, ExtraReader[] extras, long count, long timeoutMillis)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        for (;;) {
            boolean done = reader.seen >= count || !reader.isAlive();
            for (ExtraReader extra : extras)
                done &= extra.received >= count || !extra.isAlive();
            if (done)
                return;
            if (System.currentTimeMillis() > deadline)
                throw new IllegalStateException("Readers did not read the " + count + " warmup messages in " + timeoutMillis + " ms");
            Thread.sleep(1);
        }
    }

    static void run(Args args) throws Exception {
        final String url = args.string("url");
        final String key = args.string("stream");
        final ChannelPerformance performance = args.enumValue("mode", ChannelPerformance.class, ChannelPerformance.LOW_LATENCY);
        final long rate = args.longValue("rate", 200_000);
        final int burst = args.integer("burst", 1);
        final int readers = args.integer("readers", 1);
        final String[] symbols = Tb.symbols(args.integer("symbols", 1));
        final int payloadBytes = args.integer("payloadBytes", 0);
        final long warmupCount = args.longValue("warmupMessages", DEFAULT_WARMUP_MESSAGES);
        final long pacedWarmupCount = Plan.times(rate, args.longValue("warmupSeconds", DEFAULT_WARMUP_SECONDS));
        final long measureCount = measureCount(rate, args.longValue("measureSeconds", DEFAULT_MEASURE_SECONDS),
                args.longValue("measureMessages", DEFAULT_MEASURE_MESSAGES));
        final long drainMillis = TimeUnit.SECONDS.toMillis(args.integer("drainSeconds", 10));
        if (warmupCount < 0)
            throw new IllegalArgumentException("warmupMessages must not be negative, got " + warmupCount);
        if (readers < 1)
            throw new IllegalArgumentException("readers must be at least 1, got " + readers);
        if (burst < 1)
            throw new IllegalArgumentException("burst must be at least 1, got " + burst);
        if (measureCount < 1)
            throw new IllegalArgumentException("rate, measureSeconds and measureMessages must give at least one measured message");

        final long measuredFrom = warmupCount + pacedWarmupCount;
        final long total = measuredFrom + measureCount;
        final DXTickDB db = Tb.open(url, false);
        final DXTickStream stream = Tb.createStream(db, key);

        final CountDownLatch live = new CountDownLatch(readers);
        final ExtraReader[] extras = new ExtraReader[readers - 1];
        for (int r = 0; r < extras.length; r++) {
            extras[r] = new ExtraReader(Tb.liveCursor(db, stream, false), total, symbols.length, live, r);
            extras[r].start();
        }
        final TickCursor measuredCursor = Tb.liveCursor(db, stream, false);
        final MeasuredReader reader = new MeasuredReader(measuredCursor, measuredFrom, total, symbols.length, live);
        reader.start();
        if (!live.await(60, TimeUnit.SECONDS))
            throw new IllegalStateException("Readers did not go live in 60 s");

        final PerfMessage msg = new PerfMessage();
        msg.fillPayload(payloadBytes);
        final long[] symbolSequences = new long[symbols.length];
        final double nanosPerMessage = 1e9 / rate;
        long firstMeasuredSent = 0;
        long lastMeasuredSent = 0;
        long late = 0;
        final Histogram send = new Histogram(3);
        try (TickLoader<InstrumentMessage> loader = Tb.loader(stream, performance)) {
            // The warmup is sent as fast as the loader takes it, for the JIT, and is read up; then warmupSeconds at the
            // rate warm the paths of the rate itself, e.g. readers falling asleep between messages
            for (long sequence = 0; sequence < warmupCount; sequence++) {
                final int s = (int) (sequence % symbols.length);
                msg.setSymbol(symbols[s]);
                msg.sequence = sequence;
                msg.symbolSequence = symbolSequences[s]++;
                msg.sentNanos = msg.scheduledNanos = System.nanoTime();
                loader.send(msg);
            }
            loader.flush();
            awaitReaders(reader, extras, warmupCount, drainMillis);

            final long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            for (long k = warmupCount; k < total; k += burst) {
                final long due = start + (long) ((k - warmupCount) * nanosPerMessage);
                long now;
                while ((now = System.nanoTime()) < due) {
                    if (due - now > 2 * SPIN_THRESHOLD_NANOS)
                        LockSupport.parkNanos(due - now - SPIN_THRESHOLD_NANOS);
                    else
                        Thread.onSpinWait();
                }
                final long size = Math.min(burst, total - k);
                if (now - due > nanosPerMessage * size)
                    late++;
                for (long i = 0; i < size; i++) {
                    final long sequence = k + i;
                    final int s = (int) (sequence % symbols.length);
                    msg.setSymbol(symbols[s]);
                    msg.sequence = sequence;
                    msg.symbolSequence = symbolSequences[s]++;
                    msg.scheduledNanos = due;
                    msg.sentNanos = System.nanoTime();
                    loader.send(msg);
                    if (sequence >= measuredFrom)
                        send.recordValue(System.nanoTime() - msg.sentNanos);
                    if (sequence == measuredFrom)
                        firstMeasuredSent = msg.sentNanos;
                }
            }
            lastMeasuredSent = msg.sentNanos;
            loader.flush();

            final long drainDeadline = System.currentTimeMillis() + drainMillis;
            reader.join(drainMillis);
            if (reader.isAlive())
                measuredCursor.close();
            reader.join(1000);
            for (ExtraReader extra : extras) {
                extra.join(Math.max(1, drainDeadline - System.currentTimeMillis()));
                if (extra.isAlive())
                    extra.cursor.close();
            }
        }

        long extrasBehind = 0;
        final StringBuilder extraErrors = new StringBuilder();
        for (ExtraReader extra : extras) {
            if (extra.received != total)
                extrasBehind++;
            if (extra.error != null && extra.received != total)
                extraErrors.append(extra.getName()).append(": ").append(extra.error).append("; ");
        }

        final long got = reader.received;
        final ObjectNode result = Output.JSON.createObjectNode();
        result.put("messages", total);
        result.put("measuredMessages", measureCount);
        result.put("received", got);
        result.put("lost", measureCount - got);
        result.put("sequenceViolations", reader.sequenceViolations);
        if (reader.firstViolation != null)
            result.put("firstViolation", reader.firstViolation);
        result.put("negativeLatencies", reader.negativeLatencies);
        if (reader.error != null && got != measureCount)
            result.put("error", reader.error);
        result.put("extraReadersBehind", extrasBehind);
        if (extraErrors.length() > 0)
            result.put("extraReaderErrors", extraErrors.toString());
        result.put("lateBursts", late);
        if (measureCount > 1 && lastMeasuredSent > firstMeasuredSent)
            result.put("actualRate", (measureCount - 1) * 1e9 / (lastMeasuredSent - firstMeasuredSent));
        result.set("transport", Output.latency(reader.transport));
        result.set("schedule", Output.latency(reader.schedule));
        result.set("send", Output.latency(send));
        Output.result(result);
        db.close();
        System.exit(0);
    }
}
