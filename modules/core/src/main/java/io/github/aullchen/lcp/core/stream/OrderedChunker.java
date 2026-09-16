package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** Single-reader chunker with bounded outstanding leases. Source length is never assumed or pre-scanned.
 * close may be called concurrently to interrupt the source, but never revokes an outstanding lease. */
public final class OrderedChunker implements AutoCloseable {
    private final TransferSource source;
    private final Limits limits;
    private final int chunkBytes;
    private final ByteBudget budget;
    private final long peakBytes;
    private final long idleNanos;
    private final MerkleFrontier tree = new MerkleFrontier();
    private long offset;
    private volatile boolean closed;
    private boolean eof, failed;
    private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger();
    private final int window;

    /** peakBytes reserves the entire downstream lifecycle, not just the source array.
     * It must include at least chunkBytes plus the one-byte EOF probe used here. */
    public OrderedChunker(TransferSource source, int chunkBytes, Limits limits, ByteBudget budget,
                          long peakBytes, Duration zeroReadTimeout) {
        this(source, chunkBytes, limits, budget, peakBytes, zeroReadTimeout, 1);
    }
    public OrderedChunker(TransferSource source, int chunkBytes, Limits limits, ByteBudget budget,
                          long peakBytes, Duration zeroReadTimeout, int window) {
        if (window < 1 || window > 16 || window > limits.maxInFlightChunks()) throw new IllegalArgumentException("Invalid window");
        this.window = window;
        this.source = Objects.requireNonNull(source); this.limits = Objects.requireNonNull(limits);
        this.budget = Objects.requireNonNull(budget);
        if (chunkBytes <= 0 || chunkBytes > limits.maxPlainBytes() || chunkBytes > 8 * 1024 * 1024
                || peakBytes < (long) chunkBytes + 1 || peakBytes > budget.capacity())
            throw new IllegalArgumentException("Invalid chunk or peak budget");
        this.chunkBytes = chunkBytes; this.peakBytes = peakBytes;
        idleNanos = zeroReadTimeout.toNanos();
        if (idleNanos <= 0) throw new IllegalArgumentException("Invalid zero-read timeout");
    }

    /** Returns null only at EOF. Release each Pending only after all its consumers finish. */
    public Pending next() throws IOException {
        if (closed || failed) throw new IOException("Source is closed or failed");
        if (outstanding.get() >= window) throw new IllegalStateException("Previous chunk still owns its lease");
        if (eof) return null;
        ByteBudget.Lease lease = budget.reserve(peakBytes);
        try {
            long remaining = limits.maxTransferBytes() - offset;
            if (remaining == 0 || tree.count() == limits.maxChunks()) {
                if (readProgress(ByteBuffer.allocate(1)) != -1) throw new IOException("Transfer quota exceeded");
                eof = true; lease.close(); return null;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(chunkBytes, remaining));
            while (buffer.hasRemaining()) {
                if (readProgress(buffer) == -1) { eof = true; break; }
            }
            if (buffer.position() == 0) { lease.close(); return null; }
            buffer.flip();
            // Hash only populated bytes without copying the final short chunk.
            var digest = java.security.MessageDigest.getInstance("SHA-256"); digest.update(buffer.asReadOnlyBuffer());
            Bytes32 hash = new Bytes32(digest.digest());
            Chunk chunk = new Chunk(tree.count(), offset, buffer.remaining(), hash, buffer);
            tree.append(chunk.index(), offset, chunk.plainLength(), hash); offset += chunk.plainLength();
            outstanding.incrementAndGet(); return new Pending(chunk, lease);
        } catch (java.security.NoSuchAlgorithmException e) { lease.close(); failed = true; throw new AssertionError(e); }
        catch (IOException | RuntimeException e) { lease.close(); failed = true; throw e; }
    }

    private int readProgress(ByteBuffer dst) throws IOException {
        long start = System.nanoTime();
        while (true) {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source read interrupted");
            int before = dst.position(), available = dst.remaining();
            int read = source.read(dst);
            if (read < -1 || read > available || dst.position() - before != Math.max(0, read))
                throw new IOException("Source violated read contract");
            if (read != 0) return read;
            if (System.nanoTime() - start >= idleNanos) throw new IOException("Source made no progress");
            LockSupport.parkNanos(Math.min(1_000_000, idleNanos));
        }
    }

    public FinishManifest finish(UUID transferId, Bytes32 bindingHash, UUID commandId) {
        if (!eof || outstanding.get() != 0 || failed || closed) throw new IllegalStateException("Stream not drained");
        return new FinishManifest(transferId, bindingHash, commandId, tree.count(), offset, tree.root());
    }
    @Override public void close() throws IOException { closed = true; source.close(); }

    public final class Pending implements AutoCloseable {
        private final Chunk chunk;
        private final ByteBudget.Lease lease;
        private volatile boolean released;
        private Pending(Chunk chunk, ByteBudget.Lease lease) { this.chunk = chunk; this.lease = lease; }
        public Chunk chunk() {
            if (released) throw new IllegalStateException("Chunk released");
            return chunk;
        }
        /** This is ownership release, not a durable ACK. Call only after downstream users exit. */
        @Override public synchronized void close() {
            if (!released) { released = true; lease.close(); outstanding.decrementAndGet(); }
        }
    }
}
