package io.github.aullchen.lcp.api;

import io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer;
import java.io.IOException;
import java.util.UUID;

public interface TransferSink extends AutoCloseable {
    SinkSession open(AcceptedTransfer transfer) throws IOException;
    SinkSession recover(UUID transferId) throws IOException;
    /** Read a durable terminal snapshot without opening an index; null means nonterminal. */
    io.github.aullchen.lcp.api.TransferStorage.SessionState terminalState(UUID transferId) throws IOException;
    /** Finalize expired writable tasks before startup recovery or new admission. */
    void expire(java.time.Instant now) throws IOException;
    @Override void close() throws IOException;
}
