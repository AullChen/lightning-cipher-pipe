package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import java.io.IOException;
import java.util.UUID;

/** Compress once, retain immutable retry material under the original chunk lease. */
public final class PreparedChunk {
    private final long index, offset, plainLength;
    private final Bytes32 hash;
    private final int code;
    private final byte[] compressed;
    private final long compressionNanos;
    public PreparedChunk(Chunk chunk, ChunkCompression codec, int level) throws IOException {
        this(chunk, codec, level, System::nanoTime);
    }
    public PreparedChunk(Chunk chunk, ChunkCompression codec, int level, java.util.function.LongSupplier clock) throws IOException {
        index = chunk.index(); offset = chunk.offset(); plainLength = chunk.plainLength(); hash = chunk.payloadHash(); code = codec.code();
        byte[] plain = new byte[chunk.bytes().remaining()]; chunk.bytes().get(plain);
        long start = clock.getAsLong();
        compressed = codec.compress(plain, level);
        compressionNanos = clock.getAsLong() - start;
    }
    public ChunkAad aad(UUID transferId, Bytes32 binding) {
        return new ChunkAad(transferId, binding, index, offset, plainLength, compressed.length, code);
    }
    public Receipt receipt(UUID transferId) { return new Receipt(transferId, index, offset, plainLength, hash); }
    public long compressionNanos() { return compressionNanos; }
    public int compressedLength() { return compressed.length; }
    public byte[] compressedBytes() { return compressed.clone(); }
}
