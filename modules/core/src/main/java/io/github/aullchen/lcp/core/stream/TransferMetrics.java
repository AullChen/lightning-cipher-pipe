package io.github.aullchen.lcp.core.stream;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Constant-space, monotonic observations. ReceiptLedger owns progress deduplication. */
public final class TransferMetrics {
    public static final int SAMPLE_CAPACITY = 64;
    private final LongSupplier clock;
    private final long started;
    private Long stopped;
    public synchronized void finish() { stopped = tick(); }
    private long roundStarted, changed, fullNanos, budgetNanos;
    private int inFlight, window = 1, size, cursor, valid;
    private boolean blocked, eof;
    private final long[] ack = new long[SAMPLE_CAPACITY];
    private final double[] queue = new double[SAMPLE_CAPACITY];
    private long confirmed, roundConfirmed, plain, compressed, compressionNanos, sealNanos;
    private long attempts, retries, busy, budgetFailures, roundRetries, roundBusy, roundBudget;
    private long totalPlain, totalCompressed, totalCompression;
    public TransferMetrics() { this(System::nanoTime); }
    public TransferMetrics(LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        started = roundStarted = changed = clock.getAsLong();
    }
    private long tick() {
        long now = stopped == null ? clock.getAsLong() : stopped, delta = now - changed;
        if (delta < 0) throw new IllegalStateException("Monotonic clock moved backwards");
        if (inFlight >= window) fullNanos += delta;
        if (blocked) budgetNanos += delta;
        changed = now; return now;
    }
    public synchronized void window(int value, boolean budgetBlocked) {
        if (value < 1 || value > 16) throw new IllegalArgumentException("Invalid window");
        tick(); window = value; blocked = budgetBlocked;
    }
    public synchronized void sourceEof() { eof = true; }
    public synchronized void beginAttempt(boolean retry) {
        tick(); inFlight++; attempts++; if (retry) { retries++; roundRetries++; }
    }
    public synchronized void endAttempt() {
        tick(); if (inFlight == 0) throw new IllegalStateException("No active attempt"); inFlight--;
    }
    public synchronized void busy() { busy++; roundBusy++; }
    public synchronized void budgetFailure() { budgetFailures++; roundBudget++; }
    public synchronized void prepared(long plainBytes, long compressedBytes, long nanos) {
        if (plainBytes < 0 || compressedBytes < 0 || nanos < 0) throw new IllegalArgumentException("Invalid compression observation");
        plain += plainBytes; compressed += compressedBytes; compressionNanos += nanos;
        totalPlain += plainBytes; totalCompressed += compressedBytes; totalCompression += nanos;
    }
    public synchronized void sealed(long nanos) { if (nanos >= 0) sealNanos += nanos; }
    /** Absolute, deduplicated durable byte count; reconciliation contributes progress but no sample. */
    public synchronized void confirmed(long bytes) {
        if (bytes < confirmed) throw new IllegalArgumentException("Progress regressed");
        roundConfirmed += bytes - confirmed; confirmed = bytes;
    }
    public synchronized void acknowledged(long durationNanos, Long queueMicros, Long persistMicros, boolean retry, boolean repeated) {
        if (retry || repeated || durationNanos <= 0 || queueMicros == null || persistMicros == null
                || queueMicros < 0 || persistMicros < 0
                || queueMicros > durationNanos / 1000 || persistMicros > durationNanos / 1000 - queueMicros) return;
        ack[cursor] = durationNanos; queue[cursor] = queueMicros * 1000.0 / durationNanos;
        cursor = (cursor + 1) % SAMPLE_CAPACITY; size = Math.min(SAMPLE_CAPACITY, size + 1); valid++;
    }
    private Long p95() {
        if (size == 0) return null;
        long[] values = Arrays.copyOf(ack, size); Arrays.sort(values);
        return values[(int)Math.ceil(.95 * size) - 1];
    }
    private Double queueMedian() {
        if (size == 0) return null;
        double[] values = Arrays.copyOf(queue, size); Arrays.sort(values);
        return size % 2 == 1 ? values[size / 2] : (values[size / 2 - 1] + values[size / 2]) / 2;
    }
    public record Snapshot(long elapsedNanos, long confirmedBytes, long attempts, long retries, long busy,
                           long budgetFailures, long plainBytes, long compressedBytes, long compressionNanos,
                           long sealNanos, int samples, Long ackP95Nanos, Double queueShare) {}
    public synchronized Snapshot snapshot() {
        return new Snapshot(tick() - started, confirmed, attempts, retries, busy, budgetFailures,
                totalPlain, totalCompressed, totalCompression, sealNanos, size, p95(), queueMedian());
    }
    public record Round(long elapsedNanos, long confirmedBytes, int validSamples, Long ackP95Nanos, Double queueShare,
                        double compressionBusy, Double wireRatio, double windowFullShare, boolean sourceEof,
                        boolean budgetBlocked, long retries, long busy, long budgetFailures) {
        public double goodput() { return elapsedNanos > 0 ? confirmedBytes * 1_000_000_000.0 / elapsedNanos : 0; }
    }
    /** One fixed compression worker; call at a drained batch boundary. Null means keep collecting. */
    public synchronized Round pollRound() {
        long now = tick(), elapsed = now - roundStarted;
        if (valid < 16 || elapsed < 2_000_000_000L) return null;
        Round result = new Round(elapsed, roundConfirmed, valid, p95(), queueMedian(),
                Math.min(1, compressionNanos / (double)elapsed), plain == 0 ? null : compressed / (double)plain,
                fullNanos / (double)elapsed, eof, budgetNanos > 0 || blocked, roundRetries, roundBusy, roundBudget);
        roundStarted = now; roundConfirmed = plain = compressed = compressionNanos = fullNanos = budgetNanos = 0;
        roundRetries = roundBusy = roundBudget = 0; valid = 0;
        return result;
    }
}
