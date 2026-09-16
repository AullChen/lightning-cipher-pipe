package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.security.*;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** HTTPS client with bounded request bodies, control responses and total response deadlines. */
public final class AuthenticatedHttpClient implements AutoCloseable {
    private final HttpClient client;
    private final BoundedExecutor executor;
    private final Duration timeout;
    private final Semaphore permits;
    public AuthenticatedHttpClient(SSLContext context, Duration connectTimeout, Duration responseTimeout, int workers, int queued) {
        if (connectTimeout.isNegative() || connectTimeout.isZero() || responseTimeout.isNegative() || responseTimeout.isZero()) throw new IllegalArgumentException("Invalid timeout");
        executor = new BoundedExecutor(workers, queued, "lcp-http-client"); timeout = responseTimeout; permits = new Semaphore(workers);
        var ssl = context.getDefaultSSLParameters(); ssl.setProtocols(new String[]{"TLSv1.3"}); ssl.setEndpointIdentificationAlgorithm("HTTPS");
        client = HttpClient.newBuilder().sslContext(context).sslParameters(ssl).executor(executor)
                .connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Response(int status, byte[] body, TlsIdentity source, TlsIdentity target, long retryAfterMillis) {
        public Response(int status, byte[] body, TlsIdentity source, TlsIdentity target) { this(status,body,source,target,0); }
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    public Response get(URI uri, String expectedNode, String route, PeerDirectory directory, int maxBodyBytes) throws IOException, InterruptedException {
        return exchange(uri, "GET", null, new byte[0], expectedNode, route, directory, maxBodyBytes);
    }
    public Response exchange(URI uri, String method, String contentType, byte[] body, String expectedNode,
                             String route, PeerDirectory directory, int maxBodyBytes) throws IOException, InterruptedException {
        if (!List.of("GET", "POST", "PUT").contains(method) || body.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Invalid request");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || maxBodyBytes < 0 || maxBodyBytes > 65536) throw new IllegalArgumentException("Invalid HTTPS request bound");
        if (!permits.tryAcquire()) throw new IOException("Client busy");
        try (var ownedBody = new RequestBody(body)) {
            var builder = HttpRequest.newBuilder(uri).timeout(timeout);
            if (contentType != null) builder.header("Content-Type", contentType);
            var request = builder.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : ownedBody).build();
            var future = client.sendAsync(request, ignored -> new LimitedBody(maxBodyBytes));
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) { future.cancel(true); throw new HttpTimeoutException("Response deadline exceeded"); }
            catch (InterruptedException e) { future.cancel(true); throw e; }
            catch (ExecutionException e) { if (e.getCause() instanceof IOException failure) throw failure; throw new IOException("HTTPS request failed", e.getCause()); }
            var session = response.sslSession().orElseThrow(AuthenticationException::new);
            var target = TlsIdentity.peer(session, TlsIdentity.Role.SERVER);
            var source = TlsIdentity.local(session, TlsIdentity.Role.CLIENT);
            if (!target.nodeId().equals(expectedNode)) throw new AuthenticationException();
            directory.authorizeRoute(target.nodeId(), route);
            return new Response(response.statusCode(), response.body(), source, target, retryAfter(response));
        } finally { permits.release(); }
    }
    private static long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("0");
        if (!value.matches("[0-9]{1,10}")) return 0;
        return Math.min(10, Long.parseLong(value)) * 1000;
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private Flow.Subscription subscription;
        private boolean failed;
        LimitedBody(long limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer b : buffers) {
                if (b.remaining() > limit - received) { failed = true; subscription.cancel(); delegate.onError(new IOException("Response body too large")); return; }
                received += b.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable e) { if (!failed) { failed = true; delegate.onError(e); } }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() { executor.close(); }
}
