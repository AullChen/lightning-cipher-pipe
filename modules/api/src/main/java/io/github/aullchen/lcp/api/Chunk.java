package io.github.aullchen.lcp.api;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable descriptor with a borrowed, read-only payload view.
 * The owner must keep storage immutable and alive until all consumers finish. */
public record Chunk(long index, long offset, long plainLength, Bytes32 payloadHash, ByteBuffer bytes) {
    public Chunk {
        Metadata.unsigned(index); Metadata.unsigned(offset); Metadata.positive(plainLength);
        Objects.requireNonNull(payloadHash); Objects.requireNonNull(bytes);
        if (plainLength > Long.MAX_VALUE - offset || bytes.remaining() != plainLength)
            throw new IllegalArgumentException("Invalid chunk range");
        bytes = bytes.slice().asReadOnlyBuffer();
    }
    @Override public ByteBuffer bytes() { return bytes.asReadOnlyBuffer(); }
}
