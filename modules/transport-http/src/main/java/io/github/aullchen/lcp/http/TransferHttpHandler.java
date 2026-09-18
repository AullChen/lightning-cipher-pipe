package io.github.aullchen.lcp.http;

import com.sun.net.httpserver.HttpsExchange;
import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.security.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.*;

/** Fixed-policy endpoints with bounded concurrent request admission and pinned sessions. */
public final class TransferHttpHandler implements AuthenticatedHttpServer.Handler, AutoCloseable {
    public static final String BASE = "/lcp-stream/v1/transfers";
    private final TransferSink sink;
    private final HpkeKey key;
    private final PeerDirectory directory;
    private final String route, node;
    private final Limits limits;
    private final ChunkCompression optionalCodec;
    private final ByteBudget budget;
    private final Clock clock;
    private final Duration verificationTimeout;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final Semaphore slots;
    private final Semaphore controls = new Semaphore(2);
    private final BoundedExecutor verifier = new BoundedExecutor(1,1,"lcp-verify");
    private final Duration shutdownGrace;
    private volatile Verification verification;
    private volatile boolean closed;
    private record Verification(SinkSession session, FinishManifest manifest, java.util.concurrent.CompletableFuture<Void> done) {}
    private volatile SinkSession pinned;
    public TransferHttpHandler(TransferSink sink, HpkeKey key, PeerDirectory directory, String route, String node,
                               Limits limits, ByteBudget budget, Clock clock, Duration verificationTimeout) {
        this(sink, key, directory, route, node, limits, budget, clock, verificationTimeout, ChunkCompression.NONE);
    }
    public TransferHttpHandler(TransferSink sink, HpkeKey key, PeerDirectory directory, String route, String node,
                               Limits limits, ByteBudget budget, Clock clock, Duration verificationTimeout, ChunkCompression optionalCodec) {
        this(sink,key,directory,route,node,limits,budget,clock,verificationTimeout,optionalCodec,Duration.ofSeconds(60));
    }
    public TransferHttpHandler(TransferSink sink, HpkeKey key, PeerDirectory directory, String route, String node,
                               Limits limits, ByteBudget budget, Clock clock, Duration verificationTimeout, ChunkCompression optionalCodec, Duration shutdownGrace) {
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) throw new IllegalArgumentException("Invalid shutdown grace");
        this.shutdownGrace = shutdownGrace;
        this.optionalCodec = Objects.requireNonNull(optionalCodec);
        this.sink = Objects.requireNonNull(sink); this.key = Objects.requireNonNull(key); this.directory = Objects.requireNonNull(directory);
        this.route = Objects.requireNonNull(route); this.node = Objects.requireNonNull(node); this.limits = Objects.requireNonNull(limits);
        this.budget = Objects.requireNonNull(budget); this.clock = Objects.requireNonNull(clock); this.verificationTimeout = verificationTimeout;
        slots = new Semaphore(Math.toIntExact(limits.maxInFlightChunks()));
        if (limits.maxPlainBytes() > 8 * 1024 * 1024 || limits.maxInFlightChunks() > 16
                || budget.capacity() < Math.max(CompressionPlan.peak(limits, optionalCodec), 4L * MetadataCodec.CONTROL_LIMIT)
                || verificationTimeout.isNegative() || verificationTimeout.isZero()) throw new IllegalArgumentException("Invalid fixed transfer bounds");
    }
    /** Conservative live-array reservation including framing, HPKE copies, plaintext and verification scratch. */
    public static long peak(Limits limits) { return CompressionPlan.peak(limits,ChunkCompression.NONE); }
    @Override public void handle(HttpsExchange x, TlsIdentity source, TlsIdentity target) throws IOException {
        if (closed) { error(x,UNAVAILABLE); return; }
        boolean opening = BASE.equals(x.getRequestURI().getRawPath()) && "POST".equals(x.getRequestMethod());
        var lock = opening ? lifecycle.writeLock() : lifecycle.readLock();
        if (!lock.tryLock()) { error(x, BUSY); return; }
        Semaphore admission = x.getRequestURI().getRawPath().contains("/chunks/") ? slots : controls;
        if (!admission.tryAcquire()) { lock.unlock(); error(x, BUSY); return; }
        try {
            if (!node.equals(target.nodeId())) throw new AuthenticationException();
            String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
            if (BASE.equals(path) && "POST".equals(method)) {
                if (x.getRequestURI().getRawQuery() != null) throw new TransferException(INVALID_MESSAGE);
                if (running()) throw new TransferException(BUSY);
                if (pinned != null) { pinned.close(); pinned = null; }
                try (var lease = reserve(4L * MetadataCodec.CONTROL_LIMIT)) { open(x, source, target); }
                return;
            }
            if (!path.startsWith(BASE + "/")) throw new TransferException(NOT_FOUND);
            String[] parts = path.substring(BASE.length() + 1).split("/", -1);
            UUID id = uuid(parts[0]);
            {
                SinkSession session = sink.recover(id); pinned = session;
                BoundTransfer context = BoundTransfer.restore(session.state().transfer(), directory);
                if ("GET".equals(method)) {
                    context.checkReader(source); emptyBody(x);
                    if (session.state().state() == State.VERIFYING) resume(session);
                    try (var lease = reserve(4L * MetadataCodec.CONTROL_LIMIT)) {
                        if (parts.length == 1 && x.getRequestURI().getRawQuery() == null) send(x, 200, TransferJson.status(session.state()));
                        else if (parts.length == 2 && parts[1].equals("receipts")) {
                            long[] q = pageQuery(x.getRequestURI().getRawQuery());
                            send(x, 200, TransferJson.page(session.receipts(q[0], (int)q[1])));
                        } else throw new TransferException(NOT_FOUND);
                    }
                    return;
                }
                context.checkWriter(source, target);
                if (x.getRequestURI().getRawQuery() != null) throw new TransferException(INVALID_MESSAGE);
                if (parts.length == 3 && parts[1].equals("chunks") && "PUT".equals(method)) {
                    long index = unsigned(parts[2]);
                    try (var lease = reserve(CompressionPlan.peak(context.accepted().limits(), CompressionPlan.select(context.accepted().compressionCode(), optionalCodec)))) {
                        byte[] body = frameBody(x, context.accepted().limits(), context.accepted().limits().maxFrameBytes());
                        byte[] compressed = HpkeFrames.openChunk(context, key, source, target, id, index, ByteBuffer.wrap(body));
                        var frame = FrameCodec.decode(ByteBuffer.wrap(body), context.accepted().limits());
                        byte[] aadBytes = new byte[frame.aad().remaining()]; frame.aad().get(aadBytes);
                        var aad = MetadataCodec.decode(aadBytes, ChunkAad.class);
                        if (aad.plainLength() > context.accepted().policy().maxChunkBytes()) throw new TransferException(LIMIT_EXCEEDED);
                        byte[] plain = CompressionPlan.select(aad.compressionCode(), optionalCodec).decompress(compressed, Math.toIntExact(aad.plainLength()));
                        var state = session.state();
                        if (state.state() != State.COMPLETED) unexpired(context);
                        boolean repeated = !session.receipts(index, 1).entries().isEmpty();
                        var chunk = new Chunk(index, aad.offset(), plain.length, MetadataCodec.hash(plain), ByteBuffer.wrap(plain));
                        try (var reservation = session.reserve(chunk)) {
                            long admitted = System.nanoTime();
                            long start = System.nanoTime();
                            Receipt r = reservation == null ? session.commit(chunk) : reservation.commit();
                            long persist = (System.nanoTime() - start) / 1000;
                            send(x, 200, TransferJson.ack(r, reservation == null ? repeated : reservation.repeated(),
                                    reservation == null ? null : (start - admitted) / 1000, persist));
                        } catch (TransferException e) {
                            if (e.code() == CHUNK_CONFLICT && (state.state() == State.OPEN || state.state() == State.TRANSFERRING))
                                session.recordFailure(FailureKind.FAILED, CHUNK_CONFLICT);
                            throw e;
                        }
                    }
                } else if (parts.length == 2 && parts[1].equals("finish") && "POST".equals(method)) {
                    FinishManifest f;
                    try (var lease = reserve(CompressionPlan.peak(context.accepted().limits(), CompressionPlan.select(context.accepted().compressionCode(), optionalCodec)))) {
                        byte[] body = frameBody(x, context.accepted().limits(), Math.min(8192, context.accepted().limits().maxFrameBytes()));
                        f = HpkeFrames.openFinish(context, key, source, target, id, ByteBuffer.wrap(body));
                    }
                    if (session.state().finish() == null) unexpired(context);
                    startVerification(session,f);
                    Verification task = verification;
                    if (task != null) await(task,Duration.ofMillis(100),false);
                    var state = session.state();
                    if (state.state() == State.FAILED || state.state() == State.RECOVERY_REQUIRED) throw new TransferException(state.error());
                    send(x,state.state() == State.VERIFYING ? 202 : 200,TransferJson.status(state));
                } else if (parts.length == 2 && parts[1].equals("cancel") && "POST".equals(method)) {
                    try (var lease = reserve(4L * MetadataCodec.CONTROL_LIMIT)) {
                        CancelCommand command = TransferJson.cancel(body(x, "application/json", MetadataCodec.CONTROL_LIMIT));
                        if (!command.transferId().equals(id)) throw new TransferException(INVALID_MESSAGE);
                        var state = session.cancel(command);
                        Verification task = verification;
                        if (task != null && task.session() == session) await(task,shutdownGrace,true);
                        send(x, 200, TransferJson.status(state));
                    }
                } else throw new TransferException(NOT_FOUND);
            }
        } catch (AuthenticationException e) { error(x, AUTH_FAILED); }
        catch (TransferException e) { error(x, e.code()); }
        catch (ProtocolException e) { error(x, e.code() == ProtocolException.Code.LIMIT_EXCEEDED ? LIMIT_EXCEEDED : INVALID_MESSAGE); }
        catch (IllegalArgumentException e) { error(x, INVALID_MESSAGE); }
        catch (IOException e) { error(x, UNKNOWN_COMMIT); }
        finally {
            admission.release(); lock.unlock(); cleanupPinned();
        }
    }
    private boolean running() { var task = verification; return task != null && !task.done().isDone(); }
    private void cleanupPinned() throws IOException {
        if (lifecycle.writeLock().tryLock()) {
            try { if (!running() && pinned != null) { pinned.close(); pinned = null; } }
            finally { lifecycle.writeLock().unlock(); }
        }
    }
    /** Resume the durable Finish at startup or upon an authenticated status request. */
    public void resume(SinkSession session) throws IOException {
        var state = session.state();
        if (!state.transfer().request().targetNodeId().equals(node) || !state.transfer().request().routeId().equals(route)) throw new AuthenticationException();
        if (state.state() == State.VERIFYING) startVerification(session,state.finish());
    }
    private synchronized void startVerification(SinkSession session, FinishManifest manifest) throws IOException {
        if (closed) throw new TransferException(UNAVAILABLE);
        if (running()) {
            if (verification.session() != session) throw new TransferException(BUSY);
            if (!verification.manifest().equals(manifest)) throw new TransferException(COMMAND_CONFLICT);
            return;
        }
        if (session.state().state() == State.COMPLETED) { session.recordVerifying(manifest); return; }
        var lease = reserve(4L*MetadataCodec.CONTROL_LIMIT);
        try {
            session.recordVerifying(manifest); pinned = session;
            var task = new Verification(session,manifest,new java.util.concurrent.CompletableFuture<>());
            verification = task;
            verifier.execute(() -> {
                try { TransferVerifier.finish(session,manifest,clock,verificationTimeout); }
                catch (IOException ignored) { /* The verifier records the durable failure before returning. */ }
                catch (RuntimeException failure) {
                    try { session.recordFailure(FailureKind.RECOVERY_REQUIRED,UNKNOWN_COMMIT); } catch (IOException ignored) { }
                } finally { lease.close(); task.done().complete(null); }
            });
        } catch (IOException | RuntimeException e) { lease.close(); throw e; }
    }
    private static void await(Verification task, Duration timeout, boolean required) throws IOException {
        try { task.done().get(timeout.toNanos(),java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (java.util.concurrent.TimeoutException e) { if (required) throw new TransferException(UNKNOWN_COMMIT); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TransferException(UNKNOWN_COMMIT); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException("Verification worker failed",e.getCause()); }
    }
    @Override public void close() throws IOException {
        synchronized (this) { closed = true; verifier.shutdown(); }
        var task = verification; if (task != null) await(task,shutdownGrace,true);
        cleanupPinned();
    }
    private void open(HttpsExchange x, TlsIdentity source, TlsIdentity target) throws IOException {
        OpenRequest r = MetadataJson.decode(body(x, "application/json", MetadataCodec.CONTROL_LIMIT), OpenRequest.class);
        if (!r.routeId().equals(route) || !r.sourceNodeId().equals(source.nodeId()) || !r.targetNodeId().equals(node)) throw new AuthenticationException();
        SinkSession prior;
        try { prior = sink.recover(r.transferId()); }
        catch (TransferException e) { if (e.code() != NOT_FOUND) throw e; prior = null; }
        if (prior != null) {
            try (SinkSession existing = prior) {
                var stored = existing.state().transfer(); var context = BoundTransfer.restore(stored, directory);
                context.checkWriter(source, target);
                if (!stored.request().equals(r)) throw new TransferException(OPEN_CONFLICT);
                unexpired(context); send(x, 200, MetadataJson.encode(stored.response())); return;
            }
        }
        if (!r.expiresAt().isAfter(clock.instant())) throw new TransferException(EXPIRED);
        Policy p = r.policy();
        if (p.mode() != 0) throw new TransferException(INVALID_MESSAGE);
        int compressionCode = r.compressionOffers().stream().filter(c -> c == 0 || c == optionalCodec.code()).findFirst()
                .orElseThrow(() -> new TransferException(INVALID_MESSAGE));
        ChunkCompression codec = CompressionPlan.select(compressionCode, optionalCodec);
        Limits offered = r.limits();
        Limits acceptedLimits = new Limits(Math.min(limits.maxFrameBytes(), offered.maxFrameBytes()), Math.min(limits.maxPlainBytes(), offered.maxPlainBytes()),
                Math.min(limits.maxChunks(), offered.maxChunks()), Math.min(limits.maxTransferBytes(), offered.maxTransferBytes()), Math.min(limits.maxInFlightChunks(), offered.maxInFlightChunks()));
        try { CompressionPlan.validate(p, acceptedLimits, codec, budget.capacity()); }
        catch (IllegalArgumentException e) { throw new TransferException(LIMIT_EXCEEDED); }
        Policy acceptedPolicy = new Policy(0, 1, p.minChunkBytes(), p.maxChunkBytes(), p.initialChunkBytes(), p.minWindow(), p.maxWindow(), p.initialWindow(), compressionCode == 0 ? 1 : p.minZstdLevel(), compressionCode == 0 ? 1 : p.maxZstdLevel(), compressionCode == 0 ? 1 : p.initialZstdLevel());
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        Accepted a = new Accepted(r.transferId(), new Bytes32(challenge), UUID.randomUUID().toString(), compressionCode, acceptedPolicy, acceptedLimits, r.expiresAt());
        if (!key.publicHash().equals(r.targetPublicKeyHash())) throw new AuthenticationException();
        BoundTransfer context = BoundTransfer.freeze(r, a, source, target, directory, clock.instant());
        try (var session = sink.open(context.stored())) { send(x, 201, MetadataJson.encode(session.state().transfer().response())); }
    }
    private void unexpired(BoundTransfer context) throws TransferException { if (!context.accepted().expiresAt().isAfter(clock.instant())) throw new TransferException(EXPIRED); }
    private ByteBudget.Lease reserve(long bytes) throws TransferException {
        try { return budget.reserve(bytes); } catch (IllegalStateException e) { throw new TransferException(BUSY); }
    }
    private static UUID uuid(String s) throws TransferException {
        try { UUID id = UUID.fromString(s); if (!id.toString().equals(s)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException e) { throw new TransferException(INVALID_MESSAGE); }
    }
    private static long unsigned(String s) throws TransferException {
        if (s == null || !s.matches("0|[1-9][0-9]{0,18}")) throw new TransferException(INVALID_MESSAGE);
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw new TransferException(INVALID_MESSAGE); }
    }
    private static long[] pageQuery(String query) throws TransferException {
        if (query == null) throw new TransferException(INVALID_MESSAGE);
        String[] parts = query.split("&", -1); Long from = null, limit = null;
        if (parts.length != 2) throw new TransferException(INVALID_MESSAGE);
        for (String p : parts) {
            if (p.startsWith("from=") && from == null) from = unsigned(p.substring(5));
            else if (p.startsWith("limit=") && limit == null) limit = unsigned(p.substring(6));
            else throw new TransferException(INVALID_MESSAGE);
        }
        if (from == null || limit == null || limit < 1 || limit > 256) throw new TransferException(INVALID_MESSAGE);
        return new long[]{from, limit};
    }
    private static String one(HttpsExchange x, String name) throws TransferException {
        var values = x.getRequestHeaders().get(name);
        if (values == null) return null;
        if (values.size() != 1) throw new TransferException(INVALID_MESSAGE); return values.get(0);
    }
    private static void encoding(HttpsExchange x) throws TransferException {
        if (one(x, "Transfer-Encoding") != null || one(x, "Content-Encoding") != null) throw new TransferException(INVALID_MESSAGE);
    }
    private static void emptyBody(HttpsExchange x) throws TransferException {
        encoding(x); String length = one(x, "Content-Length");
        if (length != null && !length.equals("0")) throw new TransferException(INVALID_MESSAGE);
    }
    private static byte[] frameBody(HttpsExchange x, Limits limits, long max) throws IOException {
        long length = bodyLength(x, "application/lcp-frame", max);
        var frame = FrameCodec.read(x.getRequestBody(), length, limits);
        return FrameCodec.encode(frame.messageType(), bytes(frame.aad()), bytes(frame.enc()), bytes(frame.ciphertext()), limits);
    }
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
    }
    private static long bodyLength(HttpsExchange x, String type, long max) throws TransferException {
        encoding(x);
        if (!type.equals(one(x, "Content-Type"))) throw new TransferException(INVALID_MESSAGE);
        long length = unsigned(one(x, "Content-Length"));
        if (length == 0) throw new TransferException(INVALID_MESSAGE);
        if (length > max) throw new TransferException(LIMIT_EXCEEDED);
        return length;
    }
    private static byte[] body(HttpsExchange x, String type, long max) throws IOException {
        long length = bodyLength(x, type, max);
        byte[] bytes = x.getRequestBody().readNBytes((int)length);
        if (bytes.length != length || x.getRequestBody().read() != -1) throw new TransferException(INVALID_MESSAGE);
        return bytes;
    }
    private static void error(HttpsExchange x, ErrorCode code) throws IOException {
        if (code == BUSY) x.getResponseHeaders().set("Retry-After", "1");
        send(x, TransferJson.httpStatus(code), TransferJson.error(code));
    }
    private static void send(HttpsExchange x, int status, byte[] bytes) throws IOException {
        x.getResponseHeaders().set("Content-Type", "application/json"); x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
    }
}
