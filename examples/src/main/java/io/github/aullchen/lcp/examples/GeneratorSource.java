package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.TransferSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;

/** Finite deterministic test/example data; the sequence does not depend on read sizes.
 * Not cryptographic randomness. close is observed within each byte iteration. */
public final class GeneratorSource implements TransferSource {
    private final SplittableRandom random;
    private long remaining;
    private volatile boolean closed;
    public GeneratorSource(long length, long seed) {
        if (length < 0) throw new IllegalArgumentException("Negative length");
        remaining = length; random = new SplittableRandom(seed);
    }
    @Override public int read(ByteBuffer dst) throws IOException {
        if (closed) throw new IOException("Source closed");
        if (!dst.hasRemaining()) return 0;
        if (remaining == 0) return -1;
        int count = (int) Math.min(remaining, dst.remaining());
        for (int i = 0; i < count; i++) {
            if (closed) throw new IOException("Source closed");
            dst.put((byte) random.nextInt(256)); remaining--;
        }
        return count;
    }
    @Override public void close() { closed = true; }
}
