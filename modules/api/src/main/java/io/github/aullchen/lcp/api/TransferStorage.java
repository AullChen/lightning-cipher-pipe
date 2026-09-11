package io.github.aullchen.lcp.api;

import io.github.aullchen.lcp.api.Metadata.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable facts shared by the transfer engine and storage adapters. */
public final class TransferStorage {
    private TransferStorage() {}
    public enum State { OPEN, TRANSFERRING, VERIFYING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED }
    public enum FailureKind { FAILED, RECOVERY_REQUIRED }
    public enum ErrorCode {
        AUTH_FAILED, FORBIDDEN, NOT_FOUND, INVALID_MESSAGE, LIMIT_EXCEEDED, OPEN_CONFLICT,
        COMMAND_CONFLICT, STATE_CONFLICT, CHUNK_CONFLICT, MISSING_CHUNKS, BUSY,
        UNAVAILABLE, UNKNOWN_COMMIT, INTEGRITY_MISMATCH, EXPIRED
    }
    public record AcceptedTransfer(OpenRequest request, OpenResponse response,
                                   Bytes32 sourceTlsSpki, Bytes32 targetTlsSpki) {
        public AcceptedTransfer {
            Objects.requireNonNull(request); Objects.requireNonNull(response);
            Objects.requireNonNull(sourceTlsSpki); Objects.requireNonNull(targetTlsSpki);
            if (!request.transferId().equals(response.accepted().transferId())) throw new IllegalArgumentException("Transfer mismatch");
        }
    }
    public record VerifiedResult(String handleId, long totalChunks, long totalPlainBytes,
                                 Bytes32 root, Bytes32 manifestHash, Instant completedAt) {
        public VerifiedResult {
            if (handleId == null || !handleId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid handle");
            Metadata.unsigned(totalChunks); Metadata.unsigned(totalPlainBytes);
            Objects.requireNonNull(root); Objects.requireNonNull(manifestHash); Objects.requireNonNull(completedAt);
            if (completedAt.toEpochMilli() < 0 || !completedAt.equals(Instant.ofEpochMilli(completedAt.toEpochMilli())))
                throw new IllegalArgumentException("Invalid completion time");
        }
    }
    public record SessionState(AcceptedTransfer transfer, State state, long revision,
                               long committedChunks, long committedPlainBytes, FinishManifest finish,
                               VerifiedResult result, ErrorCode error, CancelCommand cancel) {
        public SessionState {
            Objects.requireNonNull(transfer); Objects.requireNonNull(state);
            Metadata.unsigned(revision); Metadata.unsigned(committedChunks); Metadata.unsigned(committedPlainBytes);
        }
    }
    public record ReceiptPage(UUID transferId, long from, int limit, List<Receipt> entries,
                              Long nextFrom, long revision) {
        public ReceiptPage { entries = List.copyOf(entries); }
    }
}
