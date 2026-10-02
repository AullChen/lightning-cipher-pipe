package io.github.aullchen.lcp.examples;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Benchmark-only admission trace. The sender never receives this schedule. */
final class ChangingCapacity {
    private final LongSupplier elapsed;
    final AtomicInteger active = new AtomicInteger();
    final AtomicLong rejected = new AtomicLong();
    ChangingCapacity(LongSupplier elapsed) { this.elapsed = elapsed; }
    int capacity() { long t=elapsed.getAsLong(); return t>=12_000_000_000L && t<28_000_000_000L ? 1 : 4; }
    boolean enter() {
        for (;;) {
            int n=active.get();
            if (n>=capacity()) { rejected.incrementAndGet(); return false; }
            if (active.compareAndSet(n,n+1)) return true;
        }
    }
    void leave() { if (active.decrementAndGet()<0) throw new IllegalStateException("Unowned admission"); }
}
