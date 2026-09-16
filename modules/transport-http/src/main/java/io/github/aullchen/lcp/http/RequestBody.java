package io.github.aullchen.lcp.http;

import java.io.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/** Detaches application-owned frame bytes even when a timed-out HTTP exchange is still retiring.
 * InputStream publishers hand the transport bounded copies, never a view into the leased frame. */
final class RequestBody implements HttpRequest.BodyPublisher, AutoCloseable {
    private byte[] bytes;
    private final int length;
    private final HttpRequest.BodyPublisher delegate;
    RequestBody(byte[] bytes) {
        this.bytes = bytes; length = bytes.length;
        delegate = HttpRequest.BodyPublishers.ofInputStream(() -> new InputStream() {
            private int position;
            @Override public int read() throws IOException {
                synchronized (RequestBody.this) {
                    availableBytes(); return position == length ? -1 : RequestBody.this.bytes[position++] & 255;
                }
            }
            @Override public int read(byte[] destination, int offset, int count) throws IOException {
                java.util.Objects.checkFromIndexSize(offset,count,destination.length);
                synchronized (RequestBody.this) {
                    availableBytes();
                    if (count == 0) return 0;
                    if (position == length) return -1;
                    int n = Math.min(count,length-position);
                    System.arraycopy(RequestBody.this.bytes,position,destination,offset,n); position += n; return n;
                }
            }
        });
    }
    private void availableBytes() throws IOException { if (bytes == null) throw new IOException("Request body released"); }
    @Override public long contentLength() { return length; }
    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) { delegate.subscribe(subscriber); }
    @Override public synchronized void close() { bytes = null; }
}
