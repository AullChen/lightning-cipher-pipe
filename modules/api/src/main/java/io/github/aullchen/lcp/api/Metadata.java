package io.github.aullchen.lcp.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Wire value objects. Authentication, negotiation and persistence are separate responsibilities. */
public final class Metadata {
    private Metadata() {}

    public static long unsigned(long value) {
        if (value < 0) throw new IllegalArgumentException("Expected unsigned signed-64-bit value");
        return value;
    }

    public static long positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("Expected positive value");
        return value;
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid identifier");
    }

    private static void time(Instant value) {
        Objects.requireNonNull(value);
        try {
            unsigned(value.toEpochMilli());
            if (!value.equals(Instant.ofEpochMilli(value.toEpochMilli())))
                throw new IllegalArgumentException("Time must have millisecond precision");
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Time overflow", e); }
    }

    private static void range(long min, long initial, long max) {
        positive(min);
        if (min > initial || initial > max) throw new IllegalArgumentException("Invalid policy range");
    }

    public static int code(int value) {
        if (value < 0 || value > 1) throw new IllegalArgumentException("Unknown code");
        return value;
    }

    public record Limits(long maxFrameBytes, long maxPlainBytes, long maxChunks,
                         long maxTransferBytes, long maxInFlightChunks) {
        public Limits {
            positive(maxFrameBytes); positive(maxPlainBytes); positive(maxChunks);
            positive(maxTransferBytes); positive(maxInFlightChunks);
            if (maxFrameBytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Frame hard limit");
        }
    }

    public record Policy(int mode, int policyVersion, long minChunkBytes, long maxChunkBytes,
                         long initialChunkBytes, long minWindow, long maxWindow, long initialWindow,
                         int minZstdLevel, int maxZstdLevel, int initialZstdLevel) {
        public Policy {
            code(mode);
            if (policyVersion != 1) throw new IllegalArgumentException("Unknown policy version");
            range(minChunkBytes, initialChunkBytes, maxChunkBytes);
            range(minWindow, initialWindow, maxWindow);
            range(minZstdLevel, initialZstdLevel, maxZstdLevel);
            if (maxZstdLevel > 5) throw new IllegalArgumentException("Invalid ZSTD level");
            if (mode == 0 && (minChunkBytes != maxChunkBytes || minWindow != maxWindow
                    || minZstdLevel != maxZstdLevel)) throw new IllegalArgumentException("FIXED must be fixed");
        }
    }

    public record OpenRequest(UUID transferId, String routeId, String sourceNodeId, String targetNodeId,
                              Bytes32 sourceChallenge, String sourceKeyId, Bytes32 sourcePublicKeyHash,
                              String targetKeyId, Bytes32 targetPublicKeyHash, List<Integer> compressionOffers,
                              Policy policy, Limits limits, Instant createdAt, Instant expiresAt) {
        public OpenRequest {
            Objects.requireNonNull(transferId); id(routeId); id(sourceNodeId); id(targetNodeId);
            Objects.requireNonNull(sourceChallenge); id(sourceKeyId); Objects.requireNonNull(sourcePublicKeyHash);
            id(targetKeyId); Objects.requireNonNull(targetPublicKeyHash);
            compressionOffers = List.copyOf(compressionOffers);
            if (compressionOffers.isEmpty() || compressionOffers.size() > 2
                    || compressionOffers.stream().distinct().count() != compressionOffers.size())
                throw new IllegalArgumentException("Invalid compression offers");
            compressionOffers.forEach(Metadata::code);
            Objects.requireNonNull(policy); Objects.requireNonNull(limits); time(createdAt); time(expiresAt);
            long lifetime = expiresAt.toEpochMilli() - createdAt.toEpochMilli();
            if (lifetime <= 0 || lifetime > 86_400_000) throw new IllegalArgumentException("Invalid lifetime");
        }
    }

    public record Accepted(UUID transferId, Bytes32 targetChallenge, String handleId, int compressionCode,
                           Policy policy, Limits limits, Instant expiresAt) {
        public Accepted {
            Objects.requireNonNull(transferId); Objects.requireNonNull(targetChallenge); id(handleId);
            code(compressionCode); Objects.requireNonNull(policy); Objects.requireNonNull(limits); time(expiresAt);
            if (policy.maxChunkBytes() > limits.maxPlainBytes() || policy.maxWindow() > limits.maxInFlightChunks())
                throw new IllegalArgumentException("Policy exceeds limits");
            if (compressionCode == 0 && (policy.minZstdLevel() != 1 || policy.maxZstdLevel() != 1))
                throw new IllegalArgumentException("NONE levels must be 1");
        }
    }

    public record OpenResponse(Accepted accepted, Bytes32 openRequestHash, Bytes32 bindingHash) {
        public OpenResponse { Objects.requireNonNull(accepted); Objects.requireNonNull(openRequestHash); Objects.requireNonNull(bindingHash); }
    }

    public record ChunkAad(UUID transferId, Bytes32 bindingHash, long chunkIndex, long offset,
                           long plainLength, long compressedLength, int compressionCode) {
        public ChunkAad {
            Objects.requireNonNull(transferId); Objects.requireNonNull(bindingHash);
            unsigned(chunkIndex); unsigned(offset); positive(plainLength); unsigned(compressedLength); code(compressionCode);
            if (plainLength > Long.MAX_VALUE - offset) throw new IllegalArgumentException("Range overflow");
            if (compressedLength > Long.MAX_VALUE - 16) throw new IllegalArgumentException("Ciphertext overflow");
            if (compressionCode == 0 && compressedLength != plainLength) throw new IllegalArgumentException("NONE length mismatch");
        }
    }

    public record FinishAad(UUID transferId, Bytes32 bindingHash, UUID commandId) {
        public FinishAad { Objects.requireNonNull(transferId); Objects.requireNonNull(bindingHash); Objects.requireNonNull(commandId); }
    }

    public record FinishManifest(UUID transferId, Bytes32 bindingHash, UUID commandId,
                                 long totalChunks, long totalPlainBytes, Bytes32 root) {
        public FinishManifest {
            Objects.requireNonNull(transferId); Objects.requireNonNull(bindingHash); Objects.requireNonNull(commandId);
            unsigned(totalChunks); unsigned(totalPlainBytes); Objects.requireNonNull(root);
            if ((totalChunks == 0) != (totalPlainBytes == 0) || totalChunks > totalPlainBytes)
                throw new IllegalArgumentException("Invalid chunk totals");
        }
    }

    public record Receipt(UUID transferId, long chunkIndex, long offset, long plainLength, Bytes32 payloadHash) {
        public Receipt {
            Objects.requireNonNull(transferId); unsigned(chunkIndex); unsigned(offset); positive(plainLength);
            Objects.requireNonNull(payloadHash);
            if (plainLength > Long.MAX_VALUE - offset) throw new IllegalArgumentException("Range overflow");
        }
    }

    public record CancelCommand(UUID transferId, UUID commandId, Bytes32 bindingHash) {
        public CancelCommand { Objects.requireNonNull(transferId); Objects.requireNonNull(commandId); Objects.requireNonNull(bindingHash); }
    }
}
