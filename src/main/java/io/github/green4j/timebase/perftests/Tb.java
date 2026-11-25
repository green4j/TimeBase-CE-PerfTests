package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.qsrv.hf.pub.RawMessage;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.epam.deltix.qsrv.hf.tickdb.pub.LoadingOptions;
import com.epam.deltix.qsrv.hf.tickdb.pub.SelectionOptions;
import com.epam.deltix.qsrv.hf.tickdb.pub.StreamOptions;
import com.epam.deltix.qsrv.hf.tickdb.pub.StreamScope;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickCursor;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickDBFactory;
import com.epam.deltix.qsrv.hf.tickdb.pub.TickLoader;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.epam.deltix.timebase.messages.service.RealTimeStartMessage;

final class Tb {

    private Tb() {
    }

    static DXTickDB open(String url, boolean readOnly) {
        final DXTickDB db = TickDBFactory.createFromUrl(url);
        db.open(readOnly);
        return db;
    }

    /** Creates the stream of a run; an existing stream with that key is never touched. */
    static DXTickStream createStream(DXTickDB db, String key) {
        if (db.getStream(key) != null)
            throw new IllegalStateException("Stream " + key + " already exists");

        final StreamOptions options = new StreamOptions();
        options.scope = StreamScope.DURABLE;
        options.setFixedType(PerfMessage.descriptor());
        return db.createStream(key, options);
    }

    static DXTickStream awaitStream(DXTickDB db, String key, long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        DXTickStream stream;
        while ((stream = db.getStream(key)) == null) {
            if (System.currentTimeMillis() > deadline)
                throw new IllegalStateException("Stream " + key + " did not appear in " + timeoutMillis + " ms");
            Thread.sleep(100);
        }
        return stream;
    }

    @SuppressWarnings("unchecked")
    static TickLoader<InstrumentMessage> loader(DXTickStream stream, ChannelPerformance performance) {
        final LoadingOptions options = new LoadingOptions();
        options.channelPerformance = performance;
        return (TickLoader<InstrumentMessage>) (TickLoader<?>) stream.createLoader(options);
    }

    /** A live cursor from the start of the stream that emits a {@link RealTimeStartMessage} once it is live. */
    static TickCursor liveCursor(DXTickDB db, DXTickStream stream, boolean raw) {
        final SelectionOptions options = new SelectionOptions(raw, true);
        options.realTimeNotification = true;
        return db.select(Long.MIN_VALUE, options, stream);
    }

    static boolean isRealTimeStart(InstrumentMessage message) {
        if (message instanceof RawMessage)
            return RealTimeStartMessage.DESCRIPTOR_GUID.equals(((RawMessage) message).type.getGuid());
        return message instanceof RealTimeStartMessage;
    }

    static String[] symbols(int count) {
        final String[] names = new String[count];
        for (int i = 0; i < count; i++)
            names[i] = String.format("S%07d", i);
        return names;
    }
}
