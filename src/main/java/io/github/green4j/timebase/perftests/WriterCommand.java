package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickLoader;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.HdrHistogram.Histogram;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;

/**
 * Writes as fast as the loader accepts, round-robin over the symbols. Measures the rate, the writer thread CPU per
 * message and the duration of {@code send()} after the warmup.
 */
final class WriterCommand {

    private WriterCommand() {
    }

    static void run(Args args) throws Exception {
        final String url = args.string("url");
        final String key = args.string("stream");
        final ChannelPerformance performance = args.enumValue("mode", ChannelPerformance.class, ChannelPerformance.MIN_CPU_USAGE);
        final String[] symbols = Tb.symbols(args.integer("symbols", 1));
        final int payloadBytes = args.integer("payloadBytes", 0);
        final long warmupNanos = TimeUnit.SECONDS.toNanos(args.integer("warmupSeconds", 5));
        final long measureNanos = TimeUnit.SECONDS.toNanos(args.integer("measureSeconds", 10));

        final DXTickDB db = Tb.open(url, false);
        final DXTickStream stream = args.bool("createStream", false)
                ? Tb.createStream(db, key)
                : Tb.awaitStream(db, key, 30_000);

        final PerfMessage msg = new PerfMessage();
        msg.fillPayload(payloadBytes);
        final long[] symbolSequences = new long[symbols.length];
        final Histogram send = new Histogram(3);
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final boolean cpuTime = threads.isCurrentThreadCpuTimeSupported();
        if (cpuTime && !threads.isThreadCpuTimeEnabled())
            threads.setThreadCpuTimeEnabled(true);

        long sequence = 0;
        long measuredFrom = -1;
        long cpuFrom = 0;
        long measuredStart = 0;
        long end;
        try (TickLoader<InstrumentMessage> loader = Tb.loader(stream, performance)) {
            final long start = System.nanoTime();
            final long measureStart = start + warmupNanos;
            final long measureEnd = measureStart + measureNanos;
            for (;;) {
                final int s = (int) (sequence % symbols.length);
                msg.setSymbol(symbols[s]);
                msg.sequence = sequence;
                msg.symbolSequence = symbolSequences[s]++;
                final long before = System.nanoTime();
                msg.sentNanos = before;
                msg.scheduledNanos = before;
                loader.send(msg);
                final long after = System.nanoTime();
                sequence++;

                if (measuredFrom >= 0) {
                    send.recordValue(after - before);
                    if (after >= measureEnd) {
                        end = after;
                        break;
                    }
                } else if (after >= measureStart) {
                    measuredFrom = sequence;
                    measuredStart = after;
                    cpuFrom = cpuTime ? threads.getCurrentThreadCpuTime() : 0;
                }
            }
            final long cpu = cpuTime ? threads.getCurrentThreadCpuTime() - cpuFrom : 0;
            final long measured = sequence - measuredFrom;

            final ObjectNode result = Output.JSON.createObjectNode();
            result.put("messages", sequence);
            result.put("measuredMessages", measured);
            result.put("rate", measured * 1e9 / (end - measuredStart));
            if (cpuTime)
                result.put("cpuNsPerMsg", (double) cpu / measured);
            result.set("send", Output.latency(send));
            Output.result(result);
        } finally {
            db.close();
        }
    }
}
