package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.api.TransferException;
import io.github.aullchen.lcp.api.TransferStorage.ErrorCode;
import java.time.*;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;

/** Four total sends. Clock, jitter and waiting are injectable for deterministic deadline tests. */
final class RetryPolicy {
    static final int MAX_ATTEMPTS = 4;
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final Clock clock;
    private final DoubleSupplier random;
    private final Sleeper sleeper;
    RetryPolicy(Clock clock) { this(clock, () -> ThreadLocalRandom.current().nextDouble(), Thread::sleep); }
    RetryPolicy(Clock clock, DoubleSupplier random, Sleeper sleeper) { this.clock = clock; this.random = random; this.sleeper = sleeper; }
    void pause(int attempts, Instant expiresAt, long retryAfterMillis) throws TransferException, InterruptedException {
        if (attempts < 1 || attempts >= MAX_ATTEMPTS) throw new TransferException(ErrorCode.UNKNOWN_COMMIT);
        long remaining = Duration.between(clock.instant(),expiresAt).toMillis();
        if (remaining <= 0) throw new TransferException(ErrorCode.EXPIRED);
        long base = 500L << (attempts-1);
        long delay = Math.min(10000, Math.max((long)(base * (0.5 + random.getAsDouble())), retryAfterMillis));
        sleeper.sleep(Math.min(remaining,delay));
        if (!expiresAt.isAfter(clock.instant())) throw new TransferException(ErrorCode.EXPIRED);
    }
}
