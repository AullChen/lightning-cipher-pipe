package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.Policy;

/** Policies v1/v2: one trial at a time, two evaluation rounds, two cooldown rounds. */
public final class FeedbackController {
    public record Parameters(int chunkBytes, int window, int zstdLevel) {}
    private final Policy policy;
    private Parameters current, previous;
    private int dimension, rounds, cooldown, trialDimension;
    private final long[][] trials = new long[3][5];
    public record TrialCounts(long started, long evaluated, long retained, long rolledBack, long interrupted) {}
    public record Diagnostics(TrialCounts window, TrialCounts compression, TrialCounts chunk) {
        public static Diagnostics empty() {
            var zero = new TrialCounts(0, 0, 0, 0, 0);
            return new Diagnostics(zero, zero, zero);
        }
    }
    private TrialCounts counts(int dimension) {
        var counts = trials[dimension];
        return new TrialCounts(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }
    public Diagnostics diagnostics() {
        return new Diagnostics(counts(0), counts(1), counts(2));
    }
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
        if (previous != null) {
            trials[trialDimension][4]++;
            current = previous;
        }
        current = new Parameters(current.chunkBytes(), Math.max((int)policy.minWindow(), current.window() / 2), current.zstdLevel());
        previous = null;
        cooldown = 2;
    }
    public Parameters observe(TransferMetrics.Round round) {
        if (policy.mode() == 0 || round == null) return current;
        if (round.busy() > 0) {
            pressure();
            return current;
        }
        if (!valid(round)) return current;
        if (policy.policyVersion() == 1 && round.queueShare() > .25) {
            pressure();
            return current;
        }
        if (previous != null) {
            trialBytes += round.confirmedBytes();
            trialNanos += round.elapsedNanos();
            budgetFailure |= round.budgetFailures() > 0;
            if (++rounds == 2) {
                double goodput = trialBytes * 1_000_000_000.0 / trialNanos;
                boolean rollback = goodput < baselineGoodput * 1.05
                        || round.ackP95Nanos() > baselineP95 * 1.2
                        || budgetFailure;
                trials[trialDimension][1]++;
                trials[trialDimension][rollback ? 3 : 2]++;
                if (rollback) current = previous;
                previous = null;
                cooldown = 2;
            }
            return current;
        }
        if (cooldown > 0) {
            cooldown--;
            return current;
        }
        for (int checked = 0; checked < 3; checked++) {
            int next = dimension;
            dimension = (dimension + 1) % 3;
            if (policy.policyVersion() == 2 && next == 1) continue; // v2 fixes compression at its initial level.
            Parameters candidate = candidate(next, round);
            if (!candidate.equals(current)) {
                previous = current;
                current = candidate;
                trialDimension = next;
                trials[next][0]++;
                baselineGoodput = round.goodput();
                baselineP95 = round.ackP95Nanos();
                trialBytes = trialNanos = 0;
                rounds = 0;
                budgetFailure = false;
                break;
            }
        }
        return current;
    }
    private Parameters candidate(int dimension, TransferMetrics.Round round) {
        int chunk = current.chunkBytes(), window = current.window(), level = current.zstdLevel();
        if (dimension == 0 && round.windowFullShare() >= .8 && !round.sourceEof() && !round.budgetBlocked()) window++;
        if (dimension == 1) {
            if (round.compressionBusy() >= .8) level--;
            else if (round.compressionBusy() <= .5 && round.windowFullShare() >= .8 && round.wireRatio() <= .9) level++;
        }
        if (dimension == 2) {
            if (round.retries() > 0) chunk /= 2;
            else if (round.windowFullShare() >= .8 && (policy.policyVersion() == 2 || round.queueShare() <= .1)) chunk *= 2;
        }
        return new Parameters((int)Math.max(policy.minChunkBytes(), Math.min(policy.maxChunkBytes(), chunk)),
                (int)Math.max(policy.minWindow(), Math.min(policy.maxWindow(), window)),
                Math.max(policy.minZstdLevel(),Math.min(policy.maxZstdLevel(),level)));
    }
    private static boolean fraction(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static boolean valid(TransferMetrics.Round round) {
        return round.elapsedNanos() >= 2_000_000_000L && round.validSamples() >= 16 && round.confirmedBytes() > 0
                && round.ackP95Nanos() != null && round.ackP95Nanos() > 0 && round.queueShare() != null && fraction(round.queueShare())
                && fraction(round.compressionBusy()) && fraction(round.windowFullShare()) && round.wireRatio() != null
                && Double.isFinite(round.wireRatio()) && round.wireRatio() >= 0 && round.retries() >= 0 && round.busy() >= 0 && round.budgetFailures() >= 0;
    }
}
