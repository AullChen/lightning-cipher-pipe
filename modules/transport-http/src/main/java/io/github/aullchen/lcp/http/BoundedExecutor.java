package io.github.aullchen.lcp.http;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed worker count and bounded queue; rejects excess work rather than retaining it. */
public final class BoundedExecutor extends ThreadPoolExecutor implements AutoCloseable {
    public BoundedExecutor(int workers, int queued, String name) {
        super(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queued), factory(name), new AbortPolicy());
    }
    private static ThreadFactory factory(String name) {
        var ids = new AtomicInteger();
        return task -> { var thread = new Thread(task, name + "-" + ids.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    @Override public void close() { shutdownNow(); }
}
