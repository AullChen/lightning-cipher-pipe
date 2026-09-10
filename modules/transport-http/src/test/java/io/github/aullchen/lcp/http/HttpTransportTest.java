package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {
    static Certificates.Identity ca, source, target;
    static SSLContext sourceContext, targetContext;
    static HpkeKey sourceKey, targetKey;
    static PeerDirectory directory;
    @BeforeAll static void setup() throws Exception {
        ca = Certificates.ca(); source = Certificates.leaf(ca, "source", true); target = Certificates.leaf(ca, "target", false);
        sourceContext = Certificates.context(source, ca, ca, "target"); targetContext = Certificates.context(target, ca, ca, "source");
        sourceKey = HpkeKey.generate(); targetKey = HpkeKey.generate();
        directory = new PeerDirectory(List.of(new PeerDirectory.Entry("source", "source-key", Set.of("demo"), sourceKey.publicSpki()), new PeerDirectory.Entry("target", "target-key", Set.of("demo"), targetKey.publicSpki())));
    }
    static AuthenticatedHttpClient client(SSLContext context, Duration timeout) { return new AuthenticatedHttpClient(context, Duration.ofSeconds(2), timeout, 2, 16); }
    static AuthenticatedHttpServer server(SSLContext context, String route, AuthenticatedHttpServer.Handler handler) throws IOException {
        return new AuthenticatedHttpServer(new InetSocketAddress("127.0.0.1", 0), context, "target", route, directory, 2, 2, handler);
    }
    static URI uri(AuthenticatedHttpServer server, String path) { return URI.create("https://localhost:" + server.port() + path); }
    static void ok(com.sun.net.httpserver.HttpsExchange exchange) throws IOException { exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[]{'o','k'}); }

    @Test void realTlsSpkiBindingAgreesAndCertificateRotationCannotResumeWrites() throws Exception {
        var zero = new Bytes32(new byte[32]); var id = UUID.randomUUID();
        var policy = new Policy(0, 1, 512, 512, 512, 1, 1, 1, 1, 1, 1);
        var limits = new Limits(10000, 1024, 100, 10000, 1);
        var request = new OpenRequest(id, "demo", "source", "target", zero, "source-key", sourceKey.publicHash(), "target-key", targetKey.publicHash(), List.of(0), policy, limits, Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var accepted = new Accepted(id, zero, "handle", 0, policy, limits, request.expiresAt());
        var frozen = new AtomicReference<BoundTransfer>(); var identities = new AtomicReference<TlsIdentity[]>();
        try (var server = server(targetContext, "demo", (exchange, s, t) -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/open")) { identities.set(new TlsIdentity[]{s,t}); frozen.set(BoundTransfer.freeze(request, accepted, s, t, directory, Instant.now())); }
                else if (exchange.getRequestURI().getPath().equals("/read")) frozen.get().checkReader(s);
                else frozen.get().checkWriter(s, t);
                ok(exchange);
            } catch (AuthenticationException e) { exchange.sendResponseHeaders(403, -1); }
        }); var client = client(sourceContext, Duration.ofSeconds(3))) {
            var response = client.get(uri(server, "/open"), "target", "demo", directory, 16);
            assertEquals(200, response.status());
            var clientBinding = BoundTransfer.freeze(request, accepted, response.source(), response.target(), directory, Instant.now());
            assertEquals(frozen.get().bindingHash(), clientBinding.bindingHash());
            assertEquals(MetadataCodec.hash(source.certificate().getPublicKey().getEncoded()), response.source().spkiHash());
            assertEquals(MetadataCodec.hash(target.certificate().getPublicKey().getEncoded()), response.target().spkiHash());
            var aad = new ChunkAad(id, clientBinding.bindingHash(), 0, 0, 1, 1, 0);
            var frame = HpkeFrames.sealChunk(clientBinding, sourceKey, response.source(), response.target(), aad, new byte[]{42});
            assertArrayEquals(new byte[]{42}, HpkeFrames.openChunk(frozen.get(), targetKey, identities.get()[0], identities.get()[1], id, 0, ByteBuffer.wrap(frame)));
            var rotated = Certificates.leaf(ca, "source", true);
            try (var nextClient = client(Certificates.context(rotated, ca, ca, "target"), Duration.ofSeconds(3))) {
                assertEquals(200, nextClient.get(uri(server, "/read"), "target", "demo", directory, 16).status());
                assertEquals(403, nextClient.get(uri(server, "/write"), "target", "demo", directory, 16).status());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"no-client", "wrong-client-ca", "wrong-server-ca", "client-node", "server-node", "missing-san", "duplicate-san", "expired", "wrong-eku", "hostname"})
    void invalidTlsNeverReachesHandler(String failure) throws Exception {
        var clientIdentity = source; var serverIdentity = target;
        var clientIssuer = ca; var serverTrust = ca; var clientTrust = ca;
        switch (failure) {
            case "no-client" -> clientIdentity = null;
            case "wrong-client-ca" -> { clientIssuer = Certificates.ca(); clientIdentity = Certificates.leaf(clientIssuer, "source", true); }
            case "wrong-server-ca" -> clientTrust = Certificates.ca();
            case "client-node" -> clientIdentity = Certificates.leaf(ca, "intruder", true);
            case "server-node" -> serverIdentity = Certificates.leaf(ca, "impostor", false);
            case "missing-san" -> clientIdentity = Certificates.leaf(ca, null, true);
            case "duplicate-san" -> clientIdentity = Certificates.leaf(ca, "source", true, true, false, "localhost");
            case "expired" -> clientIdentity = Certificates.leaf(ca, "source", true, false, true, "localhost");
            case "wrong-eku" -> clientIdentity = Certificates.leaf(ca, "source", false);
            case "hostname" -> serverIdentity = Certificates.leaf(ca, "target", false, false, false, "wrong.invalid");
        }
        var calls = new AtomicInteger();
        try (var server = server(Certificates.context(serverIdentity, ca, serverTrust, "source"), "demo", (e,s,t) -> { calls.incrementAndGet(); ok(e); });
             var client = client(Certificates.context(clientIdentity, clientIssuer, clientTrust, "target"), Duration.ofSeconds(2))) {
            assertThrows(IOException.class, () -> client.get(uri(server, "/"), "target", "demo", directory, 16));
            assertEquals(0, calls.get());
        }
    }
    @Test void unauthorizedRouteDoesNotReachHandler() throws Exception {
        var calls = new AtomicInteger();
        try (var server = server(targetContext, "denied", (e,s,t) -> { calls.incrementAndGet(); ok(e); }); var client = client(sourceContext, Duration.ofSeconds(2))) {
            assertEquals(403, client.get(uri(server, "/"), "target", "demo", directory, 16).status()); assertEquals(0, calls.get());
        }
    }
    @Test void clientResponseDeadlineAndBodyCap() throws Exception {
        var release = new CountDownLatch(1);
        try (var server = server(targetContext, "demo", (e,s,t) -> {
            if (e.getRequestURI().getPath().equals("/large")) { e.sendResponseHeaders(200, 32); e.getResponseBody().write(new byte[32]); }
            else { try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }
        }); var client = client(sourceContext, Duration.ofMillis(500))) {
            assertThrows(IOException.class, () -> client.get(uri(server, "/large"), "target", "demo", directory, 8));
            assertThrows(HttpTimeoutException.class, () -> client.get(uri(server, "/slow"), "target", "demo", directory, 16));
        } finally { release.countDown(); }
    }
    @Test void incompleteRequestBodyIsClosedByServerDeadline() throws Exception {
        var exited = new CountDownLatch(1); var entered = new CountDownLatch(1);
        try (var server = server(targetContext, "demo", (e,s,t) -> {
            entered.countDown(); try { e.getRequestBody().readAllBytes(); } finally { exited.countDown(); }
        }); var socket = (SSLSocket) sourceContext.getSocketFactory().createSocket("localhost", server.port())) {
            socket.setEnabledProtocols(new String[]{"TLSv1.3"}); socket.setSoTimeout(5000); socket.startHandshake();
            socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nx").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(exited.await(5, TimeUnit.SECONDS), "JDK request deadline must unblock body read");
        }
    }
    @Test void executorRejectsInsteadOfGrowingAndRemainsUsable() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = new BoundedExecutor(1, 1, "test")) {
            var first = executor.submit(() -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(started.await(2, TimeUnit.SECONDS)); var second = executor.submit(() -> 42);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            assertEquals(1, executor.getQueue().size()); assertEquals(1, executor.getPoolSize());
            release.countDown(); first.get(2, TimeUnit.SECONDS); assertEquals(42, second.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
    @Test void stalledResponseIsClosedByServerDeadline() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var server = server(targetContext, "demo", (e,s,t) -> {
            e.getRequestBody().readAllBytes(); entered.countDown();
            try { release.await(6, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }); var socket = (SSLSocket) sourceContext.getSocketFactory().createSocket("localhost", server.port())) {
            socket.setEnabledProtocols(new String[]{"TLSv1.3"}); socket.setSoTimeout(5000); socket.startHandshake();
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(-1, socket.getInputStream().read(), "Server response deadline must close stalled exchange");
        } finally { release.countDown(); }
    }
    @Test void unsafeServerLimitConfigurationFailsBeforeBinding() {
        String name = "jdk.httpserver.maxConnections"; String old = System.getProperty(name);
        try {
            for (String value : List.of("0", "-1", "2147483648")) {
                System.setProperty(name, value);
                assertThrows(IllegalArgumentException.class, () -> server(targetContext, "demo", (e,s,t) -> ok(e)));
            }
        } finally { System.setProperty(name, old); }
    }
}
