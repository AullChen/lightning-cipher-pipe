package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;

/** Shared sender/receiver cross-checks, including full allowed frame overhead. */
public final class CompressionPlan {
    private CompressionPlan() {}
    public static ChunkCompression select(int code, ChunkCompression optional) {
        if (code == 0) return ChunkCompression.NONE;
        if (code != 1 || optional.code() != code) throw new IllegalArgumentException("Unsupported compression");
        return optional;
    }
    public static long peak(Limits limits, ChunkCompression codec) {
        long data = Math.addExact(Math.addExact(8 * limits.maxFrameBytes(), Math.multiplyExact(2, limits.maxPlainBytes())),
                Math.addExact(65536, codec.workspaceBytes()));
        // Reconciliation reuses this lease after sends drain, while plaintext and compressed bytes remain owned.
        long retained = Math.addExact(limits.maxPlainBytes(),codec.compressBound(limits.maxPlainBytes()));
        long reconciliation = Math.addExact(4L * MetadataCodec.CONTROL_LIMIT,retained);
        return Math.max(data,reconciliation);
    }
    /** Narrow only FEEDBACK ranges; FIXED values must remain satisfiable as offered. */
    public static Policy negotiate(Policy p, Limits limits, ChunkCompression codec, long budget) {
        long chunk = p.mode() == 1 ? Math.min(p.maxChunkBytes(),limits.maxPlainBytes()) : p.maxChunkBytes();
        long window = p.mode() == 1 ? Math.min(p.maxWindow(),limits.maxInFlightChunks()) : p.maxWindow();
        Policy accepted = new Policy(p.mode(),p.policyVersion(),p.minChunkBytes(),chunk,Math.min(p.initialChunkBytes(),chunk),
                p.minWindow(),window,Math.min(p.initialWindow(),window),codec.code() == 0 ? 1 : p.minZstdLevel(),
                codec.code() == 0 ? 1 : p.maxZstdLevel(),codec.code() == 0 ? 1 : p.initialZstdLevel());
        validate(accepted,limits,codec,budget);
        return accepted;
    }
    public static void validate(Policy policy, Limits limits, ChunkCompression codec, long budget) {
        if (policy.mode() == 1 && policy.minChunkBytes() < 262144 || policy.maxChunkBytes() > 8 * 1024 * 1024 || policy.maxChunkBytes() > limits.maxPlainBytes()
                || policy.maxWindow() > 16 || policy.maxWindow() > limits.maxInFlightChunks()
                || codec.compressBound(policy.maxChunkBytes()) + 4160 > limits.maxFrameBytes()
                || budget < Math.max(262144, peak(limits, codec))) throw new IllegalArgumentException("Inconsistent compression or resource bounds");
    }
}
