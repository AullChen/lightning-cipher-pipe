package io.github.aullchen.lcp.examples;

import com.sun.net.httpserver.*;
import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.*;

/** Test-only response fault at the wire boundary, after the real handler has committed. */
final class FaultExchange extends HttpsExchange {
    @FunctionalInterface interface Transform { byte[] apply(String path, int status, byte[] body) throws IOException; }
    private final HttpsExchange delegate;
    private final Transform transform;
    private int status;
    private long length;
    FaultExchange(HttpsExchange delegate, Transform transform) { this.delegate = delegate; this.transform = transform; }
    public void sendResponseHeaders(int code,long count) { status = code; length = count; }
    public OutputStream getResponseBody() {
        return new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes,int offset,int count) {
                super.write(bytes,offset,count);
                if (size() == length) {
                    try {
                        byte[] changed = transform.apply(getRequestURI().getPath(),status,toByteArray());
                        if (changed == null) { delegate.close(); return; }
                        delegate.sendResponseHeaders(status,changed.length); delegate.getResponseBody().write(changed);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }
        };
    }
    public Headers getRequestHeaders() { return delegate.getRequestHeaders(); }
    public Headers getResponseHeaders() { return delegate.getResponseHeaders(); }
    public URI getRequestURI() { return delegate.getRequestURI(); }
    public String getRequestMethod() { return delegate.getRequestMethod(); }
    public HttpContext getHttpContext() { return delegate.getHttpContext(); }
    public void close() { delegate.close(); }
    public InputStream getRequestBody() { return delegate.getRequestBody(); }
    public InetSocketAddress getRemoteAddress() { return delegate.getRemoteAddress(); }
    public int getResponseCode() { return status; }
    public InetSocketAddress getLocalAddress() { return delegate.getLocalAddress(); }
    public String getProtocol() { return delegate.getProtocol(); }
    public Object getAttribute(String name) { return delegate.getAttribute(name); }
    public void setAttribute(String name,Object value) { delegate.setAttribute(name,value); }
    public void setStreams(InputStream input,OutputStream output) { delegate.setStreams(input,output); }
    public HttpPrincipal getPrincipal() { return delegate.getPrincipal(); }
    public SSLSession getSSLSession() { return delegate.getSSLSession(); }
}
