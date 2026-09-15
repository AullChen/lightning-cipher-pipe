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
    public PreparedChunk(Chunk chunk, ChunkCompression codec, int level) throws IOException {
        index = chunk.index(); offset = chunk.offset(); plainLength = chunk.plainLength(); hash = chunk.payloadHash(); code = codec.code();
        byte[] plain = new byte[chunk.bytes().remaining()]; chunk.bytes().get(plain);
        compressed = codec.compress(plain, level);
    }
    public ChunkAad aad(UUID transferId, Bytes32 binding) {
        return new ChunkAad(transferId, binding, index, offset, plainLength, compressed.length, code);
    }
    public Receipt receipt(UUID transferId) { return new Receipt(transferId, index, offset, plainLength, hash); }
    public byte[] compressedBytes() { return compressed.clone(); }
}
