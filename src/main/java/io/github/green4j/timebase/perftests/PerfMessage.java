package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.pub.md.Introspector;
import com.epam.deltix.qsrv.hf.pub.md.RecordClassDescriptor;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.epam.deltix.util.collections.generated.ByteArrayList;

/**
 * The only message the suite writes. {@code sequence} is global per writer, {@code symbolSequence} counts
 * messages of one symbol; the two nano times carry the open-loop schedule and the actual send time.
 */
public class PerfMessage extends InstrumentMessage {

    public long sequence;
    public long symbolSequence;
    public long scheduledNanos;
    public long sentNanos;
    public ByteArrayList payload = new ByteArrayList();

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public long getSymbolSequence() {
        return symbolSequence;
    }

    public void setSymbolSequence(long symbolSequence) {
        this.symbolSequence = symbolSequence;
    }

    public long getScheduledNanos() {
        return scheduledNanos;
    }

    public void setScheduledNanos(long scheduledNanos) {
        this.scheduledNanos = scheduledNanos;
    }

    public long getSentNanos() {
        return sentNanos;
    }

    public void setSentNanos(long sentNanos) {
        this.sentNanos = sentNanos;
    }

    public ByteArrayList getPayload() {
        return payload;
    }

    public void setPayload(ByteArrayList payload) {
        this.payload = payload;
    }

    void fillPayload(int bytes) {
        payload.clear();
        for (int i = 0; i < bytes; i++)
            payload.add((byte) (i * 31 + 7));
    }

    static RecordClassDescriptor descriptor() {
        try {
            return Introspector.createEmptyMessageIntrospector().introspectRecordClass("PerfMessage", PerfMessage.class);
        } catch (Introspector.IntrospectionException e) {
            throw new IllegalStateException("Cannot introspect PerfMessage", e);
        }
    }
}
