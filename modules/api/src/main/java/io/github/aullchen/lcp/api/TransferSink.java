package io.github.aullchen.lcp.api;

import io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer;
import java.io.IOException;
import java.util.UUID;

public interface TransferSink extends AutoCloseable {
    SinkSession open(AcceptedTransfer transfer) throws IOException;
    SinkSession recover(UUID transferId) throws IOException;
    @Override void close() throws IOException;
}
