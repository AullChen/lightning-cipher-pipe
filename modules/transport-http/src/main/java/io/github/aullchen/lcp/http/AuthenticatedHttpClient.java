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

/** Bounded control-response client. Transfer body streaming will be added with endpoint assembly. */
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
    public record Response(int status, byte[] body, TlsIdentity source, TlsIdentity target) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    public Response get(URI uri, String expectedNode, String route, PeerDirectory directory, int maxBodyBytes) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || maxBodyBytes < 0 || maxBodyBytes > 65536) throw new IllegalArgumentException("Invalid HTTPS request bound");
        if (!permits.tryAcquire()) throw new IOException("Client busy");
        try {
            var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            var future = client.sendAsync(request, ignored -> new LimitedBody(maxBodyBytes));
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) { future.cancel(true); throw new HttpTimeoutException("Response deadline exceeded"); }
            catch (InterruptedException e) { future.cancel(true); throw e; }
            catch (ExecutionException e) { throw new IOException("HTTPS request failed", e.getCause()); }
            var session = response.sslSession().orElseThrow(AuthenticationException::new);
            var target = TlsIdentity.peer(session, TlsIdentity.Role.SERVER);
            var source = TlsIdentity.local(session, TlsIdentity.Role.CLIENT);
            if (!target.nodeId().equals(expectedNode)) throw new AuthenticationException();
            directory.authorizeRoute(target.nodeId(), route);
            return new Response(response.statusCode(), response.body(), source, target);
        } finally { permits.release(); }
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
