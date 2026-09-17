package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.*;

/** Bounded persisted-output verification. The caller owns the exclusive write admission token. */
public final class TransferVerifier {
    private TransferVerifier() {}
    public static VerifiedResult finish(SinkSession session, FinishManifest manifest, Clock clock, Duration timeout) throws IOException {
        var before = session.state();
        if (before.state() == State.COMPLETED && manifest.equals(before.finish())) return before.result();
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid verification timeout");
        session.recordVerifying(manifest);
        long started = System.nanoTime();
        try {
            MerkleFrontier merkle = new MerkleFrontier(); ByteBuffer bytes = ByteBuffer.allocate(65536);
            long offset = 0;
            for (long from = 0; from < manifest.totalChunks();) {
                int size = (int) Math.min(256, manifest.totalChunks() - from);
                var page = session.receipts(from, size);
                if (page.entries().size() != size) throw new TransferException(INTEGRITY_MISMATCH);
                for (Receipt r : page.entries()) {
                    if (r.chunkIndex() != merkle.count() || r.offset() != offset) throw new TransferException(INTEGRITY_MISMATCH);
                    MessageDigest hash = sha256(); long remaining = r.plainLength();
                    while (remaining > 0) {
                        if (session.state().state() != State.VERIFYING) throw new TransferException(STATE_CONFLICT);
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= timeout.toNanos()) throw new IOException("Verification deadline exceeded");
                        bytes.clear().limit((int) Math.min(bytes.capacity(), remaining));
                        int n = session.readPersisted(offset, bytes);
                        if (n <= 0) throw new TransferException(INTEGRITY_MISMATCH);
                        bytes.flip(); hash.update(bytes); offset += n; remaining -= n;
                    }
                    Bytes32 actual = new Bytes32(hash.digest());
                    if (!actual.equals(r.payloadHash())) throw new TransferException(INTEGRITY_MISMATCH);
                    merkle.append(r.chunkIndex(), r.offset(), r.plainLength(), actual);
                }
                from += size;
            }
            bytes.clear().limit(1);
            if (offset != manifest.totalPlainBytes() || !merkle.root().equals(manifest.root())
                    || session.readPersisted(offset, bytes) != -1) throw new TransferException(INTEGRITY_MISMATCH);
            var result = new VerifiedResult(before.transfer().response().accepted().handleId(), manifest.totalChunks(),
                    offset, merkle.root(), MetadataCodec.manifestHash(manifest), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            if (Thread.currentThread().isInterrupted() || System.nanoTime()-started>=timeout.toNanos()) throw new IOException("Verification deadline exceeded");
            session.recordCompleted(result); return result;
        } catch (IOException e) {
            ErrorCode code = e instanceof TransferException t ? t.code() : UNKNOWN_COMMIT;
            try { session.recordFailure(code == INTEGRITY_MISMATCH ? FailureKind.FAILED : FailureKind.RECOVERY_REQUIRED, code); }
            catch (IOException persist) { e.addSuppressed(persist); }
            throw e;
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
