package io.github.aullchen.lcp.api;

import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Implementations acknowledge only durable receipts; close never deletes output. */
public interface SinkSession extends AutoCloseable {
    SessionState state() throws IOException;
    void recordTransferring() throws IOException;
    Receipt commit(Chunk chunk) throws IOException;
    ReceiptPage receipts(long fromIndex, int limit) throws IOException;
    int readPersisted(long offset, ByteBuffer dst) throws IOException;
    void recordVerifying(FinishManifest manifest) throws IOException;
    void recordCompleted(VerifiedResult result) throws IOException;
    void recordFailure(FailureKind kind, ErrorCode code) throws IOException;
    SessionState cancel(CancelCommand command) throws IOException;
    @Override void close() throws IOException;
}
