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
        final ByteBudget targetBudget = new ByteBudget(64 * 1024 * 1024), sourceBudget = new ByteBudget(64 * 1024 * 1024);
        final AuthenticatedHttpClient http = new AuthenticatedHttpClient(sourceTls, Duration.ofSeconds(3), TIMEOUT, 5, 32);
        final FixedTransferClient sender = new FixedTransferClient(http, sourceKey, directory, sourceBudget, clock, new io.github.aullchen.lcp.compression.ZstdCompression());
        final AuthenticatedHttpServer server;
        final TransferHttpHandler handler;
        final URI endpoint;
        Pair() throws Exception { this(new FileSink.Io() {}); }
        Pair(FileSink.Io io) throws Exception { this(io, LIMITS); }
        Pair(FileSink.Io io, Limits bounds) throws Exception { this(io,bounds,h -> h); }
        Pair(FileSink.Io io, Limits bounds, java.util.function.UnaryOperator<AuthenticatedHttpServer.Handler> decorate) throws Exception {
            sink = new FileSink(root, bounds.maxChunks() * 52, io);
            handler = new TransferHttpHandler(sink, targetKey, directory, "demo", "target", bounds, targetBudget, clock, TIMEOUT, new io.github.aullchen.lcp.compression.ZstdCompression());
            server = new AuthenticatedHttpServer(new InetSocketAddress("127.0.0.1", 0), targetTls, "target", "demo", directory, 5, 16, decorate.apply(handler));
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
        @Override public void close() throws Exception { server.close(); handler.close(); sender.close(); http.close(); sink.close(); }
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
    @Test void concurrentWindowCompletesWithSourceOrderRoot() throws Exception {
        var bounds = new Limits(300000, 262144, 128, 16 * 1024 * 1024, 3);
        CountDownLatch writes = new CountDownLatch(3);
        var io = new FileSink.Io() {
            @Override public void after(FileSink.Boundary b) throws IOException {
                if (b == FileSink.Boundary.PAYLOAD_WRITTEN) {
                    writes.countDown();
                    try { if (!writes.await(5, TimeUnit.SECONDS)) throw new IOException("Writes were serialized"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
            }
        };
        try (var pair = new Pair(io, bounds)) {
            var old = pair.request(); var policy = new Policy(0,1,262144,262144,262144,3,3,3,1,1,1);
            var request = new OpenRequest(old.transferId(),old.routeId(),old.sourceNodeId(),old.targetNodeId(),old.sourceChallenge(),
                    old.sourceKeyId(),old.sourcePublicKeyHash(),old.targetKeyId(),old.targetPublicKeyHash(),List.of(0),policy,bounds,old.createdAt(),old.expiresAt());
            var result = pair.sender.transfer(pair.endpoint, request, new GeneratorSource(7 * 262144 + 3, 42));
            assertEquals(8, result.totalChunks());
            var random = new SplittableRandom(42);
            byte[] output = Files.readAllBytes(root.resolve(request.transferId() + "/payload.bin"));
            for (byte b : output) assertEquals((byte) random.nextInt(256), b);
            assertEquals(0, pair.sourceBudget.used()); assertEquals(0, pair.targetBudget.used());
        }
    }
    @Test void lostDurableAckIsReconciledAcrossEveryAssignedPage() throws Exception {
        var bounds = new Limits(300000,262144,300,1000,1);
        var dropped = new java.util.concurrent.atomic.AtomicBoolean();
        var pages = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        try (var pair = new Pair(new FileSink.Io(){},bounds,handler -> (x,source,target) ->
                handler.handle(new FaultExchange(x,(path,code,body) -> {
                    if (path.endsWith("/chunks/269") && code == 200 && !dropped.getAndSet(true)) return null;
                    if (path.endsWith("/receipts") && code == 200) pages.add(TransferJson.page(body).from());
                    return body;
                }),source,target))) {
            var request = requestWith(pair, bounds, 1, 1);
            var result = pair.sender.transfer(pair.endpoint,request,new GeneratorSource(270,42));
            assertEquals(270,result.totalChunks()); assertTrue(dropped.get());
            assertEquals(List.of(0L,256L),pages);
            assertEquals(270 * 68,Files.size(root.resolve(request.transferId()+"/receipts.log")));
            assertEquals(0,pair.sourceBudget.used());
        }
    }
    @Test void missingPreviouslyAcknowledgedReceiptFreezesWithoutReadingNextChunk() throws Exception {
        var dropped = new java.util.concurrent.atomic.AtomicBoolean();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        try (var pair = new Pair(new FileSink.Io(){},LIMITS,handler -> (x,source,target) ->
                handler.handle(new FaultExchange(x,(path,code,body) -> {
                    if (path.endsWith("/chunks/1") && code == 200 && !dropped.getAndSet(true)) return null;
                    if (path.endsWith("/receipts") && code == 200) {
                        var page = TransferJson.page(body);
                        return TransferJson.page(new ReceiptPage(page.transferId(),page.from(),page.limit(),
                                page.entries().stream().filter(r -> r.chunkIndex()!=0).toList(),page.nextFrom(),page.revision()));
                    }
                    return body;
                }),source,target))) {
            var request = requestWith(pair,LIMITS,1,1);
            TransferSource input = new TransferSource() {
                public int read(ByteBuffer dst) { reads.incrementAndGet(); dst.put((byte)1); return 1; }
                public void close() {}
            };
            assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,() -> pair.sender.transfer(pair.endpoint,request,input)).code());
            assertEquals(2,reads.get()); assertEquals(0,pair.sourceBudget.used());
            assertEquals(2,pair.status(request.transferId()).committedChunks());
        }
    }
    @Test void fullBudgetRetriesFreshFramesBeforeNewSourceReads() throws Exception {
        var bounds = new Limits(300000,262144,128,16*1024*1024,3);
        var attempts = new java.util.concurrent.atomic.AtomicIntegerArray(3);
        var firstFrames = new java.util.concurrent.ConcurrentHashMap<Integer,byte[]>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        try (var pair = new Pair(new FileSink.Io(){},bounds,handler -> (x,source,target) -> {
            String path = x.getRequestURI().getPath();
            if (path.contains("/chunks/")) {
                int index = Integer.parseInt(path.substring(path.lastIndexOf('/')+1));
                if (index < 3) {
                    byte[] frame = x.getRequestBody().readAllBytes();
                    if (attempts.incrementAndGet(index) == 1) {
                        firstFrames.put(index,frame); byte[] error = TransferJson.error(ErrorCode.BUSY);
                        x.getResponseHeaders().set("Retry-After","0"); x.sendResponseHeaders(429,error.length); x.getResponseBody().write(error); return;
                    }
                    assertEquals(3,reads.get()); assertFalse(Arrays.equals(firstFrames.get(index),frame));
                    x.setStreams(new ByteArrayInputStream(frame),x.getResponseBody());
                }
            }
            handler.handle(x,source,target);
        })) {
            var request = requestWith(pair,bounds,262144,3);
            var budget = new ByteBudget(3*CompressionPlan.peak(bounds,ChunkCompression.NONE));
            var generated = new GeneratorSource(3*262144+1,42);
            TransferSource input = new TransferSource() {
                public int read(ByteBuffer dst) throws IOException { reads.incrementAndGet(); return generated.read(dst); }
                public void close() { generated.close(); }
            };
            try (var sender = new FixedTransferClient(pair.http,sourceKey,directory,budget,pair.clock)) {
                var result = sender.transfer(pair.endpoint,request,input); assertEquals(4,result.totalChunks());
            }
            for (int i=0;i<3;i++) assertEquals(2,attempts.get(i));
            assertEquals(0,budget.used()); assertEquals(4*68,Files.size(root.resolve(request.transferId()+"/receipts.log")));
        }
    }
    @Test void busyCountsTowardFourSendLimitAndCancelsWithoutNewReads() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger(); var reads = new java.util.concurrent.atomic.AtomicInteger();
        try (var pair = new Pair(new FileSink.Io(){},LIMITS,handler -> (x,source,target) -> {
            if (x.getRequestURI().getPath().contains("/chunks/")) {
                x.getRequestBody().readAllBytes(); attempts.incrementAndGet(); byte[] error = TransferJson.error(ErrorCode.BUSY);
                x.sendResponseHeaders(429,error.length); x.getResponseBody().write(error); return;
            }
            handler.handle(x,source,target);
        })) {
            var request = requestWith(pair,LIMITS,1,1);
            TransferSource input = new TransferSource() {
                public int read(ByteBuffer dst) { reads.incrementAndGet(); dst.put((byte)1); return 1; }
                public void close() {}
            };
            assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,() -> pair.sender.transfer(pair.endpoint,request,input)).code());
            assertEquals(4,attempts.get()); assertEquals(1,reads.get());
            assertEquals(State.CANCELLED,pair.status(request.transferId()).state()); assertEquals(0,pair.sourceBudget.used());
        }
    }
    @Test void lostFinishResponseReturnsOnlyVerifiedCompletedStatus() throws Exception {
        var dropped = new java.util.concurrent.atomic.AtomicBoolean();
        try (var pair = new Pair(new FileSink.Io(){},LIMITS,handler -> (x,source,target) ->
                handler.handle(new FaultExchange(x,(path,code,body) -> {
                    if (path.endsWith("/finish") && code == 200 && !dropped.getAndSet(true)) return null;
                    return body;
                }),source,target))) {
            var request = pair.request();
            var result = pair.sender.transfer(pair.endpoint,request,new GeneratorSource(1,42));
            assertTrue(dropped.get()); assertEquals(1,result.totalPlainBytes()); assertEquals(0,pair.sourceBudget.used());
        }
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ErrorCode.class,names={"AUTH_FAILED","CHUNK_CONFLICT","UNKNOWN_COMMIT"})
    void definitiveFailuresAreNeverRetried(ErrorCode code) throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var queries = new java.util.concurrent.atomic.AtomicInteger();
        try (var pair = new Pair(new FileSink.Io(){},LIMITS,handler -> (x,source,target) -> {
            if (x.getRequestMethod().equals("GET")) queries.incrementAndGet();
            if (x.getRequestURI().getPath().contains("/chunks/")) {
                x.getRequestBody().readAllBytes(); attempts.incrementAndGet(); byte[] error = TransferJson.error(code);
                x.sendResponseHeaders(TransferJson.httpStatus(code),error.length); x.getResponseBody().write(error); return;
            }
            handler.handle(x,source,target);
        })) {
            assertEquals(code,assertThrows(TransferException.class,() -> pair.sender.transfer(pair.endpoint,pair.request(),new GeneratorSource(1,42))).code());
            assertEquals(1,attempts.get()); assertEquals(0,queries.get()); assertEquals(0,pair.sourceBudget.used());
        }
    }
    @Test void timeoutReconcilesAbsentChunkBeforeRetryAndKeepsLease() throws Exception {
        var timedOut = new CountDownLatch(1); var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var budget = new ByteBudget(CompressionPlan.peak(LIMITS,ChunkCompression.NONE));
        try (var pair = new Pair(new FileSink.Io(){},LIMITS,handler -> (x,source,target) -> {
            if (x.getRequestURI().getPath().contains("/chunks/") && attempts.incrementAndGet()==1) {
                x.getRequestBody().readAllBytes();
                try { assertTrue(timedOut.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                x.close(); return;
            }
            if (x.getRequestMethod().equals("GET")) { assertEquals(budget.capacity(),budget.used()); timedOut.countDown(); }
            handler.handle(x,source,target);
        }); var http = new AuthenticatedHttpClient(sourceTls,Duration.ofSeconds(2),Duration.ofSeconds(2),3,16);
             var sender = new FixedTransferClient(http,sourceKey,directory,budget,pair.clock)) {
            var result = sender.transfer(pair.endpoint,pair.request(),new GeneratorSource(1,42));
            assertEquals(1,result.totalChunks()); assertEquals(2,attempts.get()); assertEquals(0,budget.used());
        } finally { timedOut.countDown(); }
    }
    @Test void preparationFailureClosesSourceAndItsUnsubmittedLease() throws Exception {
        try (var pair = new Pair()) {
            var closed = new java.util.concurrent.atomic.AtomicInteger();
            var badCodec = new ChunkCompression() {
                public int code() { return 1; }
                public long compressBound(long size) { return size+1024; }
                public long workspaceBytes() { return 0; }
                public byte[] compress(byte[] plain,int level) throws IOException { throw new IOException("Injected preparation failure"); }
                public byte[] decompress(byte[] bytes,int length) { throw new AssertionError(); }
            };
            var old = pair.request();
            var request = new OpenRequest(old.transferId(),old.routeId(),old.sourceNodeId(),old.targetNodeId(),old.sourceChallenge(),
                    old.sourceKeyId(),old.sourcePublicKeyHash(),old.targetKeyId(),old.targetPublicKeyHash(),List.of(1),old.policy(),old.limits(),old.createdAt(),old.expiresAt());
            TransferSource input = new TransferSource() {
                public int read(ByteBuffer dst) { dst.put(new byte[dst.remaining()]); return dst.position(); }
                public void close() { closed.incrementAndGet(); }
            };
            try (var sender = new FixedTransferClient(pair.http,sourceKey,directory,pair.sourceBudget,pair.clock,badCodec)) {
                assertThrows(IOException.class,() -> sender.transfer(pair.endpoint,request,input));
            }
            assertEquals(1,closed.get()); assertEquals(0,pair.sourceBudget.used());
        }
    }
    @Test void oneBackgroundVerifierRejectsLateWritesAndCancellationWins() throws Exception {
        var reading=new CountDownLatch(1); var release=new CountDownLatch(1); var cancelling=new CountDownLatch(1);
        var reads=new java.util.concurrent.atomic.AtomicInteger(); var pool=Executors.newSingleThreadExecutor();
        try (var pair=new Pair(new FileSink.Io(){
            public int read(java.nio.channels.FileChannel channel,ByteBuffer bytes,long offset) throws IOException {
                reads.incrementAndGet(); reading.countDown(); LifecycleStorageTest.await(release); return channel.read(bytes,offset);
            }
            public void cancelling() { cancelling.countDown(); }
        })) {
            var request=pair.request(); var opened=pair.open(request);
            assertEquals(200,pair.call(opened.path()+"/chunks/0","PUT","application/lcp-frame",opened.chunk(0,0,(byte)1)).status());
            var manifest=opened.manifest(new byte[]{1});
            assertEquals(202,pair.call(opened.path()+"/finish","POST","application/lcp-frame",opened.finish(manifest)).status());
            LifecycleStorageTest.await(reading);
            assertEquals(202,pair.call(opened.path()+"/finish","POST","application/lcp-frame",opened.finish(manifest)).status());
            assertEquals(1,reads.get()); assertEquals(State.VERIFYING,pair.status(request.transferId()).state());
            error(pair.call(opened.path()+"/chunks/1","PUT","application/lcp-frame",opened.chunk(1,1,(byte)2)),ErrorCode.STATE_CONFLICT);
            var command=new CancelCommand(request.transferId(),UUID.randomUUID(),opened.context.bindingHash());
            var cancelled=pool.submit(() -> pair.call(opened.path()+"/cancel","POST","application/json",TransferJson.cancel(command)));
            LifecycleStorageTest.await(cancelling); assertFalse(cancelled.isDone()); assertTrue(pair.targetBudget.used()>0);
            release.countDown(); assertEquals(State.CANCELLED,TransferJson.status(cancelled.get(3,TimeUnit.SECONDS).body()).state());
            assertEquals(State.CANCELLED,pair.status(request.transferId()).state()); assertEquals(0,pair.targetBudget.used());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void restartedTargetResumesSavedFinishWithOriginalIdentity() throws Exception {
        OpenRequest request; FinishManifest manifest; VerifiedResult completed;
        try (var pair=new Pair()) {
            request=pair.request(); var opened=pair.open(request);
            assertEquals(200,pair.call(opened.path()+"/chunks/0","PUT","application/lcp-frame",opened.chunk(0,0,(byte)1)).status());
            manifest=opened.manifest(new byte[]{1});
            try (var session=pair.sink.recover(request.transferId())) { session.recordVerifying(manifest); }
        }
        try (var restarted=new Pair()) {
            var pending=restarted.sink.pendingVerification(); assertTrue(pending.isPresent()); restarted.handler.resume(pending.get());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            TransferJson.Status status;
            do { status=restarted.status(request.transferId()); if (status.state()==State.VERIFYING) Thread.sleep(10); }
            while (status.state()==State.VERIFYING && System.nanoTime()<deadline);
            assertEquals(State.COMPLETED,status.state()); assertEquals(manifest.commandId(),status.finish().commandId());
            completed=status.result(); assertNotNull(completed);
            assertEquals(completed,restarted.sender.recover(restarted.endpoint,request,null).result());
        }
        assertArrayEquals(new byte[]{1},Files.readAllBytes(root.resolve(request.transferId()+"/payload.bin")));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void restartedSourceCancelsOldIntentThenStartsNewIdFromZero(boolean acceptedSaved) throws Exception {
        try (var pair=new Pair()) {
            var old=pair.request(); var opened=pair.open(old);
            assertEquals(200,pair.call(opened.path()+"/chunks/0","PUT","application/lcp-frame",opened.chunk(0,0,(byte)7)).status());
            var accepted=acceptedSaved ? opened.context.stored() : null;
            assertNull(pair.sender.recover(pair.endpoint,old,accepted));
            assertEquals(State.CANCELLED,pair.status(old.transferId()).state());
            var fresh=pair.request(); assertNotEquals(old.transferId(),fresh.transferId());
            var result=pair.sender.transfer(pair.endpoint,fresh,new GeneratorSource(17,42));
            assertEquals(17,result.totalPlainBytes()); assertEquals(1,result.totalChunks());
            assertEquals(17,Files.size(root.resolve(fresh.transferId()+"/payload.bin")));
            assertArrayEquals(new byte[]{7},Files.readAllBytes(root.resolve(old.transferId()+"/payload.bin")));
        }
    }
    @Test void senderCloseTimeoutRetainsLeaseUntilCompressionConsumerExits() throws Exception {
        var compressing=new CountDownLatch(1); var release=new CountDownLatch(1); var closes=new java.util.concurrent.atomic.AtomicInteger();
        try (var pair=new Pair()) {
            var codec=new ChunkCompression() {
                public int code() { return 1; }
                public long compressBound(long n) { return n+1024; }
                public long workspaceBytes() { return 0; }
                public byte[] compress(byte[] bytes,int level) throws IOException { compressing.countDown(); LifecycleStorageTest.await(release); return bytes; }
                public byte[] decompress(byte[] bytes,int length) { throw new AssertionError(); }
            };
            var old=pair.request(); var request=new OpenRequest(old.transferId(),old.routeId(),old.sourceNodeId(),old.targetNodeId(),old.sourceChallenge(),old.sourceKeyId(),old.sourcePublicKeyHash(),old.targetKeyId(),old.targetPublicKeyHash(),List.of(1),old.policy(),old.limits(),old.createdAt(),old.expiresAt());
            TransferSource input=new TransferSource() {
                boolean read;
                public int read(ByteBuffer bytes) { if (read) return -1; read=true; bytes.put((byte)1); return 1; }
                public void close() { closes.incrementAndGet(); }
            };
            var sender=new FixedTransferClient(pair.http,sourceKey,directory,pair.sourceBudget,pair.clock,codec);
            var result=sender.start(pair.endpoint,request,input).toCompletableFuture();
            try {
                LifecycleStorageTest.await(compressing);
                assertThrows(TransferException.class,() -> sender.close(Duration.ofMillis(30)));
                assertFalse(result.isDone()); assertTrue(pair.sourceBudget.used()>0); assertEquals(1,closes.get());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class,() -> result.get(3,TimeUnit.SECONDS)); sender.close();
            assertEquals(0,pair.sourceBudget.used()); assertEquals(1,closes.get());
        } finally { release.countDown(); }
    }
    @ParameterizedTest @ValueSource(ints={8192,16384})
    void smallFrameReconciliationKeepsControlAndRetainedPayloadReserved(int frameBytes) throws Exception {
        var bounds=new Limits(frameBytes,1,4,4,2);
        long required=4L*MetadataCodec.CONTROL_LIMIT+2;
        var budget=new ByteBudget(required);
        var busy=new java.util.concurrent.atomic.AtomicBoolean();
        var observed=new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
        try (var pair=new Pair(new FileSink.Io(){},bounds,handler -> (x,source,target) -> {
            if (x.getRequestURI().getPath().endsWith("/chunks/0") && !busy.getAndSet(true)) {
                x.getRequestBody().readAllBytes(); byte[] error=TransferJson.error(ErrorCode.BUSY);
                x.sendResponseHeaders(429,error.length); x.getResponseBody().write(error); return;
            }
            if (x.getRequestMethod().equals("GET")) observed.accumulateAndGet(budget.used(),Math::min);
            handler.handle(x,source,target);
        }); var sender=new FixedTransferClient(pair.http,sourceKey,directory,budget,pair.clock)) {
            var result=sender.transfer(pair.endpoint,requestWith(pair,bounds,1,2),new GeneratorSource(2,42));
            assertEquals(2,result.totalPlainBytes());
            assertNotEquals(Long.MAX_VALUE,observed.get());
            assertTrue(observed.get()>=required,"Reconciliation borrowed unreserved control memory: "+observed.get());
            assertEquals(0,budget.used());
        }
    }
    private static OpenRequest requestWith(Pair pair, Limits bounds, int chunk, int window) {
        var old = pair.request(); var policy = new Policy(0,1,chunk,chunk,chunk,window,window,window,1,1,1);
        return new OpenRequest(old.transferId(),old.routeId(),old.sourceNodeId(),old.targetNodeId(),old.sourceChallenge(),
                old.sourceKeyId(),old.sourcePublicKeyHash(),old.targetKeyId(),old.targetPublicKeyHash(),List.of(0),policy,bounds,old.createdAt(),old.expiresAt());
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
    }    @ParameterizedTest @ValueSource(strings = {"NONE-repeat", "NONE-random", "ZSTD-repeat", "ZSTD-random", "ZSTD-empty"})
    void codecsShareAuthenticatedEndToEndFlow(String mode) throws Exception {
        try (var pair = new Pair()) {
            var r = pair.request(); int code = mode.startsWith("ZSTD") ? 1 : 0;
            var p = r.policy();
            var policy = new Policy(0,1,p.minChunkBytes(),p.maxChunkBytes(),p.initialChunkBytes(),1,1,1,code == 1 ? 3 : 1,code == 1 ? 3 : 1,code == 1 ? 3 : 1);
            r = new OpenRequest(r.transferId(),r.routeId(),r.sourceNodeId(),r.targetNodeId(),r.sourceChallenge(),r.sourceKeyId(),r.sourcePublicKeyHash(),r.targetKeyId(),r.targetPublicKeyHash(),List.of(code),policy,r.limits(),r.createdAt(),r.expiresAt());
            byte[] input = new byte[mode.endsWith("empty") ? 0 : 524289]; if (mode.endsWith("random")) new Random(42).nextBytes(input);
            Path file = Files.createTempFile("lcp-codec-input", ".bin");
            try {
                Files.write(file, input);
                var result = pair.sender.transfer(pair.endpoint, r, new FileSource(file));
                assertEquals(input.length, result.totalPlainBytes());
                assertArrayEquals(input, Files.readAllBytes(root.resolve(r.transferId() + "/payload.bin")));
                assertEquals(0, pair.sourceBudget.used()); assertEquals(0, pair.targetBudget.used());
            } finally { Files.deleteIfExists(file); }
        }
    }
}
