package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.api.Metadata;
import java.nio.ByteBuffer;
import java.util.List;

/** Ordered RFC 6962 tree; at most 63 hashes, no retained payload or leaf list. Not thread-safe. */
public final class MerkleFrontier {
    private final Bytes32[] levels = new Bytes32[63];
    private long count;

    public static Bytes32 leaf(long index, long offset, long length, Bytes32 payloadHash) {
        Metadata.unsigned(index); Metadata.unsigned(offset); Metadata.positive(length);
        if (length > Long.MAX_VALUE - offset) throw new IllegalArgumentException("Range overflow");
        byte[] metadata = CanonicalCbor.encode(List.of(index, offset, length, payloadHash.bytes()));
        return MetadataCodec.hash(ByteBuffer.allocate(1 + metadata.length).put((byte) 0).put(metadata).array());
    }

    private static Bytes32 node(Bytes32 left, Bytes32 right) {
        return MetadataCodec.hash(ByteBuffer.allocate(65).put((byte) 1).put(left.bytes()).put(right.bytes()).array());
    }

    public void append(long index, long offset, long length, Bytes32 payloadHash) {
        if (index != count || count == Long.MAX_VALUE) throw new IllegalArgumentException("Non-sequential index");
        Bytes32 hash = leaf(index, offset, length, payloadHash);
        int level = 0;
        for (long n = count; (n & 1) != 0; n >>>= 1) {
            hash = node(levels[level], hash); levels[level++] = null;
        }
        levels[level] = hash; count++;
    }

    public long count() { return count; }
    public Bytes32 root() {
        Bytes32 root = null;
        for (Bytes32 level : levels) if (level != null) root = root == null ? level : node(level, root);
        return root == null ? MetadataCodec.hash(new byte[0]) : root;
    }
}
