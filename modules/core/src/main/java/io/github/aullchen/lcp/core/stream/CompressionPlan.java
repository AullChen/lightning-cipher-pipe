package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.*;

/** Shared sender/receiver cross-checks, including full allowed frame overhead. */
public final class CompressionPlan {
    private CompressionPlan() {}
    public static ChunkCompression select(int code, ChunkCompression optional) {
        if (code == 0) return ChunkCompression.NONE;
        if (code != 1 || optional.code() != code) throw new IllegalArgumentException("Unsupported compression");
        return optional;
    }
    public static long peak(Limits limits, ChunkCompression codec) {
        return Math.addExact(Math.addExact(8 * limits.maxFrameBytes(), Math.multiplyExact(2, limits.maxPlainBytes())),
                Math.addExact(65536, codec.workspaceBytes()));
    }
    public static void validate(Policy policy, Limits limits, ChunkCompression codec, long budget) {
        if (policy.maxChunkBytes() > 8 * 1024 * 1024 || policy.maxChunkBytes() > limits.maxPlainBytes()
                || policy.maxWindow() > limits.maxInFlightChunks()
                || codec.compressBound(policy.maxChunkBytes()) + 4160 > limits.maxFrameBytes()
                || budget < Math.max(262144, peak(limits, codec))) throw new IllegalArgumentException("Inconsistent compression or resource bounds");
    }
}
