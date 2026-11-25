package io.github.green4j.timebase.perftests;

import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickDB;
import com.epam.deltix.qsrv.hf.tickdb.pub.DXTickStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A server the suite does not manage. Runs share its state with each other and with whatever else uses it, so
 * their results are marked as not isolated. The stream of a run is deleted when the run ends.
 */
final class ExternalServer implements Server {

    private final String url;
    private final String stream;

    private ExternalServer(String url, String stream) {
        this.url = url;
        this.stream = stream;
    }

    static ExternalServer connect(String url, String stream) throws Exception {
        Server.awaitReady(url, 0, () -> true, 30_000);
        return new ExternalServer(url, stream);
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public boolean isolated() {
        return false;
    }

    @Override
    public JsonNode describe() {
        final ObjectNode node = Output.JSON.createObjectNode();
        node.put("url", url);
        return node;
    }

    @Override
    public void close() {
        final DXTickDB db = Tb.open(url, false);
        try {
            final DXTickStream s = db.getStream(stream);
            if (s != null)
                s.delete();
        } finally {
            db.close();
        }
    }
}
