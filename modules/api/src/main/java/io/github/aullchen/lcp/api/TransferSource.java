package io.github.aullchen.lcp.api;

import java.io.IOException;
import java.nio.ByteBuffer;

/** A finite blocking byte stream. Short reads and zero are not EOF; -1 is EOF.
 * Implementations must allow close to interrupt a blocked read within their documented bound.
 * The caller owns dst; the source must not retain it. */
public interface TransferSource extends AutoCloseable {
    int read(ByteBuffer dst) throws IOException;
    @Override void close() throws IOException;
}
