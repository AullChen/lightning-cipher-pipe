package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.http.*;
import io.github.aullchen.lcp.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferIntegrationTest extends StorageTestSupport {
    static SSLContext sourceTls, targetTls;
    static HpkeKey sourceKey, targetKey;
    static PeerDirectory directory;
    static final Limits LIMITS = new Limits(300000, 262144, 128, 16 * 1024 * 1024, 1);
    static final Duration TIMEOUT = Duration.ofSeconds(8);
    @BeforeAll static void certificates() throws Exception {
        var ca = Certificates.ca();
        sourceTls = Certificates.context(Certificates.leaf(ca, "source", true), ca, ca, "target");
        targetTls = Certificates.context(Certificates.leaf(ca, "target", false), ca, ca, "source");
        sourceKey = HpkeKey.generate(); targetKey = HpkeKey.generate();
        directory = new PeerDirectory(List.of(new PeerDirectory.Entry("source", "source-key", Set.of("demo"), sourceKey.publicSpki()),
                new PeerDirectory.Entry("target", "target-key", Set.of("demo"), targetKey.publicSpki())));
    }
    static class TestClock extends Clock {
        volatile Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    class Pair implements AutoCloseable {
        final TestClock clock = new TestClock();
        final FileSink sink;
        final ByteBudget targetBudget = new ByteBudget(4 * 1024 * 1024), sourceBudget = new ByteBudget(4 * 1024 * 1024);
        final AuthenticatedHttpClient http = new AuthenticatedHttpClient(sourceTls, Duration.ofSeconds(3), TIMEOUT, 2, 16);
        final FixedTransferClient sender = new FixedTransferClient(http, sourceKey, directory, sourceBudget, clock);
        final AuthenticatedHttpServer server;
        final URI endpoint;
        Pair() throws Exception { this(new FileSink.Io() {}); }
        Pair(FileSink.Io io) throws Exception {
            sink = new FileSink(root, 128 * 52, io);
            var handler = new TransferHttpHandler(sink, targetKey, directory, "demo", "target", LIMITS, targetBudget, clock, TIMEOUT);
            server = new AuthenticatedHttpServer(new InetSocketAddress("127.0.0.1", 0), targetTls, "target", "demo", directory, 2, 8, handler);
            endpoint = URI.create("https://localhost:" + server.port() + TransferHttpHandler.BASE);
        }
        OpenRequest request() {
            var p = new Policy(0, 1, 262144, 262144, 262144, 1, 1, 1, 1, 1, 1);
            return new OpenRequest(UUID.randomUUID(), "demo", "source", "target", MetadataCodec.hash(new byte[]{1}), "source-key", sourceKey.publicHash(),
                    "target-key", targetKey.publicHash(), List.of(0), p, LIMITS, clock.now, clock.now.plusSeconds(300));
        }
        AuthenticatedHttpClient.Response call(String path, String method, String type, byte[] body) throws Exception {
            return http.exchange(URI.create(endpoint + path), method, type, body, "target", "demo", directory, 65536);
        }
        Opened open(OpenRequest request) throws Exception {
            var r = call("", "POST", "application/json", MetadataJson.encode(request)); assertEquals(201, r.status(), new String(r.body(), StandardCharsets.UTF_8));
            var response = MetadataJson.decode(r.body(), OpenResponse.class);
            return new Opened(BoundTransfer.freeze(request, response.accepted(), r.source(), r.target(), directory, clock.now), r.source(), r.target());
        }
        TransferJson.Status status(UUID id) throws Exception { var r = call("/" + id, "GET", null, new byte[0]); assertEquals(200, r.status()); return TransferJson.status(r.body()); }
        @Override public void close() throws Exception { server.close(); sender.close(); http.close(); sink.close(); }
    }
    record Opened(BoundTransfer context, TlsIdentity source, TlsIdentity target) {
        String path() { return "/" + context.request().transferId(); }
        byte[] chunk(long index, long offset, byte... bytes) {
            return HpkeFrames.sealChunk(context, sourceKey, source, target,
                    new ChunkAad(context.request().transferId(), context.bindingHash(), index, offset, bytes.length, bytes.length, 0), bytes);
        }
        FinishManifest manifest(byte[] bytes) {
            MerkleFrontier m = new MerkleFrontier(); if (bytes.length > 0) m.append(0, 0, bytes.length, MetadataCodec.hash(bytes));
            return new FinishManifest(context.request().transferId(), context.bindingHash(), UUID.randomUUID(), bytes.length == 0 ? 0 : 1, bytes.length, m.root());
        }
        byte[] finish(FinishManifest f) { return HpkeFrames.sealFinish(context, sourceKey, source, target, f); }
    }
    static void error(AuthenticatedHttpClient.Response response, ErrorCode code) {
        assertEquals(TransferJson.httpStatus(code), response.status(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(code, TransferJson.error(response.body()));
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 262145, 8388609})
    void realTlsTransferCompletesOnlyWithMatchingPersistedOutput(int length) throws Exception {
        try (var pair = new Pair()) {
            OpenRequest request = pair.request();
            VerifiedResult result = pair.sender.start(pair.endpoint, request, new GeneratorSource(length, 42)).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(length, result.totalPlainBytes()); assertEquals((length + 262143L) / 262144, result.totalChunks());
            assertEquals(State.COMPLETED, pair.status(request.transferId()).state());
            byte[] actual = Files.readAllBytes(root.resolve(request.transferId() + "/payload.bin"));
            var random = new SplittableRandom(42); byte[] expected = new byte[length]; for (int i = 0; i < length; i++) expected[i] = (byte) random.nextInt(256);
            assertArrayEquals(expected, actual); assertEquals(0, pair.sourceBudget.used()); assertEquals(0, pair.targetBudget.used());
        }
    }
    @Test void durableOpenReplayConflictBusyAndExpiry() throws Exception {
        try (var pair = new Pair()) {
            OpenRequest request = pair.request(); Opened opened = pair.open(request);
            var replay = pair.call("", "POST", "application/json", MetadataJson.encode(request)); assertEquals(200, replay.status());
            assertEquals(opened.context.response(), MetadataJson.decode(replay.body(), OpenResponse.class));
            var changed = new OpenRequest(request.transferId(), request.routeId(), request.sourceNodeId(), request.targetNodeId(), MetadataCodec.hash(new byte[]{2}),
                    request.sourceKeyId(), request.sourcePublicKeyHash(), request.targetKeyId(), request.targetPublicKeyHash(), request.compressionOffers(), request.policy(), request.limits(), request.createdAt(), request.expiresAt());
            error(pair.call("", "POST", "application/json", MetadataJson.encode(changed)), ErrorCode.OPEN_CONFLICT);
            error(pair.call("", "POST", "application/json", MetadataJson.encode(pair.request())), ErrorCode.BUSY);
            pair.clock.now = request.expiresAt();
            error(pair.call("", "POST", "application/json", MetadataJson.encode(request)), ErrorCode.EXPIRED);
            error(pair.call(opened.path() + "/chunks/0", "PUT", "application/lcp-frame", opened.chunk(0, 0, (byte)1)), ErrorCode.EXPIRED);
            assertEquals(0, pair.status(request.transferId()).committedChunks());
        }
    }
    @Test void expiredFirstOpenCreatesNoTransfer() throws Exception {
        try (var pair = new Pair()) {
            OpenRequest r = pair.request(); pair.clock.now = r.expiresAt();
            error(pair.call("", "POST", "application/json", MetadataJson.encode(r)), ErrorCode.EXPIRED);
            assertFalse(Files.exists(root.resolve(r.transferId().toString())));
        }
    }
    @Test void tamperedFrameHasNoDurableSideEffectsAndValidRetryWorks() throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request()); byte[] frame = o.chunk(0, 0, (byte)1); frame[frame.length - 1] ^= 1;
            error(pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", frame), ErrorCode.AUTH_FAILED);
            assertEquals(0, pair.status(o.context.request().transferId()).committedChunks());
            assertEquals(200, pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, (byte)1)).status());
        }
    }
    @Test void duplicateChunkIsStableAndConflictFreezesActiveTransfer() throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request()); byte[] frame = o.chunk(0, 0, (byte)7);
            var first = pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", frame); assertEquals(200, first.status());
            long revision = pair.status(o.context.request().transferId()).revision();
            var duplicate = TransferJson.ack(pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", frame).body());
            assertEquals("ALREADY_APPLIED", duplicate.disposition()); assertNull(duplicate.persistMicros()); assertEquals(revision, pair.status(o.context.request().transferId()).revision());
            var page = pair.call(o.path() + "/receipts?limit=2&from=0", "GET", null, new byte[0]);
            assertEquals(1, TransferJson.page(page.body()).entries().size());
            error(pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, (byte)8)), ErrorCode.CHUNK_CONFLICT);
            assertEquals(State.FAILED, pair.status(o.context.request().transferId()).state());
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(root.resolve(o.context.request().transferId() + "/payload.bin")));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"modified", "truncated", "extended", "wrong-root"})
    void finishRejectsCorruptOutput(String damage) throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request()); byte[] bytes = {1,2,3};
            assertEquals(200, pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, bytes)).status());
            Path payload = root.resolve(o.context.request().transferId() + "/payload.bin");
            switch (damage) {
                case "modified" -> Files.write(payload, new byte[]{1,2,4});
                case "truncated" -> Files.write(payload, new byte[]{1});
                case "extended" -> Files.write(payload, new byte[]{1,2,3,4});
                default -> { }
            }
            var f = o.manifest(damage.equals("wrong-root") ? new byte[]{1,2,4} : bytes);
            var response = pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(f));
            if (damage.equals("truncated")) { error(response, ErrorCode.STATE_CONFLICT); assertEquals(State.RECOVERY_REQUIRED, pair.status(f.transferId()).state()); }
            else { error(response, ErrorCode.INTEGRITY_MISMATCH); assertEquals(State.FAILED, pair.status(f.transferId()).state()); }
        }
    }
    @Test void missingChunksCanBeFilledThenFinishIsIdempotent() throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request()); byte[] bytes = {1,2}; var f = o.manifest(bytes);
            error(pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(f)), ErrorCode.MISSING_CHUNKS);
            assertEquals(State.OPEN, pair.status(f.transferId()).state());
            assertEquals(200, pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, bytes)).status());
            assertEquals(200, pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(f)).status());
            var result = pair.status(f.transferId());
            assertEquals(200, pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(f)).status());
            assertEquals(result, pair.status(f.transferId()));
            assertEquals(200, pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, bytes)).status());
            error(pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, (byte)9)), ErrorCode.CHUNK_CONFLICT);
            error(pair.call(o.path() + "/chunks/1", "PUT", "application/lcp-frame", o.chunk(1, 2, (byte)3)), ErrorCode.STATE_CONFLICT);
            error(pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(o.manifest(bytes))), ErrorCode.COMMAND_CONFLICT);
            assertEquals(result, pair.status(f.transferId()));
        }
    }
    @Test void admissionIsNonblockingDuringDurableWrite() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pair = new Pair(new FileSink.Io() {
            @Override public void after(FileSink.Boundary boundary) throws IOException {
                if (boundary == FileSink.Boundary.PAYLOAD_WRITTEN) {
                    writing.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Barrier timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
            }
        })) {
            Opened o = pair.open(pair.request()); var pool = Executors.newSingleThreadExecutor();
            try {
                Future<AuthenticatedHttpClient.Response> commit = pool.submit(() -> pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, (byte)1)));
                assertTrue(writing.await(4, TimeUnit.SECONDS)); assertTrue(pair.targetBudget.used() > 0);
                error(pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(o.manifest(new byte[]{1}))), ErrorCode.BUSY);
                release.countDown(); assertEquals(200, commit.get(4, TimeUnit.SECONDS).status()); assertEquals(0, pair.targetBudget.used());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"missing-length", "chunked", "encoding", "oversized", "wrong-type", "duplicate-length"})
    void requestBoundaryRejectsBeforeOpenAllocation(String kind) throws Exception {
        try (var pair = new Pair(); var socket = (SSLSocket)sourceTls.getSocketFactory().createSocket("localhost", pair.server.port())) {
            socket.setSoTimeout(4000); socket.startHandshake();
            String extra = switch (kind) {
                case "missing-length" -> "";
                case "chunked" -> "Transfer-Encoding: chunked\r\n";
                case "encoding" -> "Content-Encoding: gzip\r\nContent-Length: 2\r\n";
                case "oversized" -> "Content-Length: 65537\r\n";
                case "duplicate-length" -> "Content-Length: 2\r\nContent-Length: 3\r\n";
                default -> "Content-Length: 2\r\n";
            };
            String type = kind.equals("wrong-type") ? "multipart/form-data" : "application/json";
            String request = "POST " + TransferHttpHandler.BASE + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: " + type + "\r\n" + extra + "\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertNotNull(line); assertTrue(line.contains(kind.equals("oversized") ? "413" : "400"), line);
        }
    }
    @Test void sourceClosesExactlyOnceOnSuccessAndOpenFailure() throws Exception {
        try (var pair = new Pair()) {
            for (boolean expired : new boolean[]{false, true}) {
                var closes = new java.util.concurrent.atomic.AtomicInteger();
                TransferSource source = new TransferSource() {
                    @Override public int read(ByteBuffer b) { return -1; }
                    @Override public void close() { assertEquals(1, closes.incrementAndGet()); }
                };
                OpenRequest r = pair.request(); if (expired) pair.clock.now = r.expiresAt();
                if (expired) assertThrows(TransferException.class, () -> pair.sender.transfer(pair.endpoint, r, source));
                else pair.sender.transfer(pair.endpoint, r, source);
                assertEquals(1, closes.get()); assertEquals(0, pair.sourceBudget.used());
            }
        }
    }
    @Test void cancelIsDurableAndCannotReuseFinishCommandId() throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request()); var f = o.manifest(new byte[0]);
            String json = "{\"transferId\":\"" + f.transferId() + "\",\"commandId\":\"" + f.commandId()
                    + "\",\"bindingHash\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(f.bindingHash().bytes()) + "\"}";
            byte[] command = json.getBytes(StandardCharsets.UTF_8);
            assertEquals(200, pair.call(o.path() + "/cancel", "POST", "application/json", command).status());
            assertEquals(200, pair.call(o.path() + "/cancel", "POST", "application/json", command).status());
            assertEquals(State.CANCELLED, pair.status(f.transferId()).state());
            error(pair.call(o.path() + "/finish", "POST", "application/lcp-frame", o.finish(f)), ErrorCode.COMMAND_CONFLICT);
            error(pair.call(o.path() + "/chunks/0", "PUT", "application/lcp-frame", o.chunk(0, 0, (byte)1)), ErrorCode.STATE_CONFLICT);
        }
    }
    @Test void invalidFramePrefixIsRejectedWithoutWaitingForCiphertext() throws Exception {
        try (var pair = new Pair()) {
            Opened o = pair.open(pair.request());
            try (var socket = (SSLSocket)sourceTls.getSocketFactory().createSocket("localhost", pair.server.port())) {
                socket.setSoTimeout(3000); socket.startHandshake();
                String headers = "PUT " + TransferHttpHandler.BASE + o.path() + "/chunks/0 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/lcp-frame\r\nContent-Length: 300000\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(new byte[16]); socket.getOutputStream().flush();
                String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertNotNull(line); assertTrue(line.contains("400"), line);
            }
            assertEquals(0, pair.status(o.context.request().transferId()).committedChunks());
            assertEquals(0, pair.targetBudget.used());
        }
    }}
