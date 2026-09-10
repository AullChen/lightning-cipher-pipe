package io.github.aullchen.lcp.core.stream;

/** Non-blocking byte reservations. A lease remains owned until every user of its buffers exits. */
public final class ByteBudget {
    private final long capacity;
    private long used;
    public ByteBudget(long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid budget");
        this.capacity = capacity;
    }
    public synchronized Lease reserve(long bytes) {
        if (bytes <= 0 || bytes > capacity) throw new IllegalArgumentException("Invalid reservation");
        if (bytes > capacity - used) throw new IllegalStateException("Byte budget exhausted");
        used += bytes; return new Lease(bytes);
    }
    public long capacity() { return capacity; }
    public synchronized long used() { return used; }
    public final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean released;
        private Lease(long bytes) { this.bytes = bytes; }
        @Override public void close() {
            synchronized (ByteBudget.this) {
                if (!released) { used -= bytes; released = true; }
            }
        }
    }
}
