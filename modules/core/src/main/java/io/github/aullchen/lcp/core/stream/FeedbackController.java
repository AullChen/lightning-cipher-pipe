package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.Policy;

/** Policy v1: one trial at a time, two evaluation rounds, two cooldown rounds. */
public final class FeedbackController {
    public record Parameters(int chunkBytes, int window, int zstdLevel) {}
    private final Policy policy;
    private Parameters current, previous;
    private int dimension, rounds, cooldown;
    private double baselineGoodput;
    private long baselineP95, trialBytes, trialNanos;
    private boolean budgetFailure;
    public FeedbackController(Policy policy) {
        if (policy.minChunkBytes() < 262144 || policy.maxChunkBytes() > 8388608 || policy.maxWindow() > 16)
            throw new IllegalArgumentException("Unsupported feedback bounds");
        this.policy = policy;
        current = new Parameters(Math.toIntExact(policy.initialChunkBytes()), Math.toIntExact(policy.initialWindow()), policy.initialZstdLevel());
    }
    public Parameters parameters() { return current; }
    public boolean trialActive() { return previous != null; }
    public int cooldownRounds() { return cooldown; }
    /** Authenticated BUSY may interrupt before enough valid ACKs exist for a round. */
    public void pressure() {
        if (policy.mode() == 0) return;
        if (previous != null) current = previous;
        current = new Parameters(current.chunkBytes(), Math.max((int)policy.minWindow(), current.window()/2), current.zstdLevel());
        previous = null; cooldown = 2;
    }
    public Parameters observe(TransferMetrics.Round r) {
        if (policy.mode() == 0 || r == null) return current;
        if (r.busy() > 0) { pressure(); return current; }
        if (!valid(r)) return current;
        if (r.queueShare() > .25) { pressure(); return current; }
        if (previous != null) {
            trialBytes += r.confirmedBytes(); trialNanos += r.elapsedNanos();
            budgetFailure |= r.budgetFailures() > 0;
            if (++rounds == 2) {
                double goodput = trialBytes * 1_000_000_000.0 / trialNanos;
                if (goodput < baselineGoodput * 1.05 || r.ackP95Nanos() > baselineP95 * 1.2 || budgetFailure) current = previous;
                previous = null; cooldown = 2;
            }
            return current;
        }
        if (cooldown > 0) { cooldown--; return current; }
        for (int checked = 0; checked < 3; checked++) {
            int next = dimension; dimension = (dimension + 1) % 3;
            Parameters candidate = candidate(next, r);
            if (!candidate.equals(current)) {
                previous = current; current = candidate;
                baselineGoodput = r.goodput(); baselineP95 = r.ackP95Nanos();
                trialBytes = trialNanos = 0; rounds = 0; budgetFailure = false;
                break;
            }
        }
        return current;
    }
    private Parameters candidate(int dimension, TransferMetrics.Round r) {
        int chunk = current.chunkBytes(), window = current.window(), level = current.zstdLevel();
        if (dimension == 0 && r.windowFullShare() >= .8 && !r.sourceEof() && !r.budgetBlocked()) window++;
        if (dimension == 1) {
            if (r.compressionBusy() >= .8) level--;
            else if (r.compressionBusy() <= .5 && r.windowFullShare() >= .8 && r.wireRatio() <= .9) level++;
        }
        if (dimension == 2) {
            if (r.retries() > 0) chunk /= 2;
            else if (r.windowFullShare() >= .8 && r.queueShare() <= .1) chunk *= 2;
        }
        return new Parameters((int)Math.max(policy.minChunkBytes(), Math.min(policy.maxChunkBytes(),chunk)),
                (int)Math.max(policy.minWindow(),Math.min(policy.maxWindow(),window)),
                Math.max(policy.minZstdLevel(),Math.min(policy.maxZstdLevel(),level)));
    }
    private static boolean fraction(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static boolean valid(TransferMetrics.Round r) {
        return r.elapsedNanos() >= 2_000_000_000L && r.validSamples() >= 16 && r.confirmedBytes() > 0
                && r.ackP95Nanos() != null && r.ackP95Nanos() > 0 && r.queueShare() != null && fraction(r.queueShare())
                && fraction(r.compressionBusy()) && fraction(r.windowFullShare()) && r.wireRatio() != null
                && Double.isFinite(r.wireRatio()) && r.wireRatio() >= 0 && r.retries() >= 0 && r.busy() >= 0 && r.budgetFailures() >= 0;
    }
}
