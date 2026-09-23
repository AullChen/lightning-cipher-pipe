package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.security.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.*;

/** Bounded-window FIXED/FEEDBACK sender. Uncertain writes are reconciled before bounded retries. */
public final class FixedTransferClient implements AutoCloseable {
    private final AuthenticatedHttpClient http;
    private final HpkeKey key;
    private final PeerDirectory directory;
    private final ByteBudget budget;
    private final Clock clock;
    private final ChunkCompression optionalCodec;
    private final long metadataBudget;
    private final RetryPolicy retries;
    private final BoundedExecutor worker = new BoundedExecutor(1, 1, "lcp-source");
    private final AtomicBoolean active = new AtomicBoolean();
    private final Object completion = new Object();
    private volatile TransferMetrics metrics = new TransferMetrics();
    public TransferMetrics.Snapshot metrics() { return metrics.snapshot(); }
    private volatile boolean closed;
    private volatile TransferSource activeSource;
    public FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock) {
        this(http, key, directory, budget, clock, ChunkCompression.NONE);
    }
    public FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock, ChunkCompression optionalCodec) {
        this(http, key, directory, budget, clock, optionalCodec, 128L * 1024 * 1024);
    }
    public FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock,
                               ChunkCompression optionalCodec, long metadataBudget) {
        this(http,key,directory,budget,clock,optionalCodec,metadataBudget,new RetryPolicy(clock));
    }
    FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock,
                        ChunkCompression optionalCodec, long metadataBudget, RetryPolicy retries) {
        if (metadataBudget < 52) throw new IllegalArgumentException("Invalid metadata budget");
        this.metadataBudget = metadataBudget; this.retries = retries;
        this.optionalCodec = java.util.Objects.requireNonNull(optionalCodec);
        this.http = java.util.Objects.requireNonNull(http); this.key = java.util.Objects.requireNonNull(key);
        this.directory = java.util.Objects.requireNonNull(directory); this.budget = java.util.Objects.requireNonNull(budget);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    /** Ownership of source passes only when submission succeeds; the worker closes it on every outcome. */
    public CompletionStage<VerifiedResult> start(URI endpoint, OpenRequest request, TransferSource source) {
        return start(endpoint,request,source,ignored -> {});
    }
    public CompletionStage<VerifiedResult> start(URI endpoint, OpenRequest request, TransferSource source, OpenObserver observer) {
        var task = new AsyncTransfer(endpoint, request, source, observer);
        worker.execute(task);
        return task;
    }
    private final class AsyncTransfer extends CompletableFuture<VerifiedResult> implements Runnable {
        private final URI endpoint;
        private final OpenRequest request;
        private final TransferSource source;
        private final OpenObserver observer;
        private Thread runner;
        AsyncTransfer(URI endpoint, OpenRequest request, TransferSource original, OpenObserver observer) {
            this.endpoint = endpoint; this.request = request; this.observer = observer;
            var released = new AtomicBoolean();
            this.source = new TransferSource() {
                public int read(java.nio.ByteBuffer dst) throws IOException {
                    if (isCancelled()) throw new TransferException(UNAVAILABLE);
                    return original.read(dst);
                }
                public void close() throws IOException { if (released.compareAndSet(false,true)) original.close(); }
            };
        }
        @Override public void run() {
            synchronized (this) {
                if (isCancelled()) return;
                runner = Thread.currentThread();
            }
            try { complete(transfer(endpoint,request,source,observer,this::isCancelled)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); completeExceptionally(e); }
            catch (Throwable e) { completeExceptionally(e); }
            finally { synchronized (this) { runner = null; } }
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            // Completing a future can invoke caller callbacks that wait for this worker to exit.
            if (!super.cancel(mayInterruptIfRunning)) return false;
            synchronized (this) {
                if (mayInterruptIfRunning && runner != null) runner.interrupt();
            }
            worker.remove(this);
            try { source.close(); }
            catch (IOException e) { throw new CompletionException(e); }
            return true;
        }
    }
    @FunctionalInterface public interface OpenObserver { void accepted(AcceptedTransfer transfer) throws IOException; }
    public VerifiedResult transfer(URI endpoint, OpenRequest request, TransferSource source) throws IOException, InterruptedException {
        return transfer(endpoint,request,source,ignored -> {});
    }
    public VerifiedResult transfer(URI endpoint, OpenRequest request, TransferSource source, OpenObserver observer) throws IOException, InterruptedException {
        return transfer(endpoint,request,source,observer,() -> false);
    }
    private VerifiedResult transfer(URI endpoint, OpenRequest request, TransferSource source, OpenObserver observer,
                                    BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (closed || cancelled.getAsBoolean()) { source.close(); throw new TransferException(UNAVAILABLE); }
        if (!active.compareAndSet(false, true)) { source.close(); throw new TransferException(BUSY); }
        TransferSource original=source; var sourceClosed=new AtomicBoolean();
        source=new TransferSource() {
            public int read(java.nio.ByteBuffer dst) throws IOException { return original.read(dst); }
            public void close() throws IOException { if (sourceClosed.compareAndSet(false,true)) original.close(); }
        };
        activeSource=source;
        metrics = new TransferMetrics();
        boolean chunkerOwnsSource = false;
        try {
            if (closed || cancelled.getAsBoolean()) throw new TransferException(UNAVAILABLE);
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !TransferHttpHandler.BASE.equals(endpoint.getRawPath()))
                throw new IllegalArgumentException("Expected transfer HTTPS endpoint");
            if (request.limits().maxChunks() > metadataBudget / 52 || request.limits().maxChunks() > Integer.MAX_VALUE / 49)
                throw new IllegalArgumentException("Receipt history exceeds metadata budget");
            for (int offer : request.compressionOffers()) {
                ChunkCompression codec = CompressionPlan.select(offer, optionalCodec);
                CompressionPlan.validate(request.policy(), request.limits(), codec, budget.capacity());
            }
            BoundTransfer context; TlsIdentity sourceTls, targetTls;
            try (var lease = budget.reserve(4L * MetadataCodec.CONTROL_LIMIT)) {
                var response = http.exchange(endpoint, "POST", "application/json", MetadataJson.encode(request),
                        request.targetNodeId(), request.routeId(), directory, MetadataCodec.CONTROL_LIMIT);
                success(response);
                OpenResponse opened = MetadataJson.decode(response.body(), OpenResponse.class);
                sourceTls = response.source(); targetTls = response.target();
                context = BoundTransfer.freeze(request, opened.accepted(), sourceTls, targetTls, directory, clock.instant());
                if (!context.response().equals(opened)) throw new AuthenticationException();
            }
            observer.accepted(context.stored());
            live(context,cancelled);
            ChunkCompression codec = CompressionPlan.select(context.accepted().compressionCode(), optionalCodec);
            CompressionPlan.validate(context.accepted().policy(), context.accepted().limits(), codec, budget.capacity());
            long peak = CompressionPlan.peak(context.accepted().limits(), codec);
            URI transferUri = URI.create(endpoint.toString() + "/" + request.transferId());
            var ledger = new ReceiptLedger(request.transferId(),context.accepted().limits().maxChunks(),metadataBudget);
            FinishManifest finish;
            Policy policy = context.accepted().policy();
            var controller = policy.mode() == 1 ? new FeedbackController(policy) : null;
            int capacity = (int)Math.min(policy.maxWindow(), budget.capacity() / peak);
            var sends = new BoundedExecutor(capacity,capacity,"lcp-chunk");
            try (var chunks = new OrderedChunker(source, Math.toIntExact(context.accepted().policy().initialChunkBytes()),
                    context.accepted().limits(), budget, peak, Duration.ofSeconds(10), capacity)) {
                chunkerOwnsSource = true;
                pipeline(sends,chunks,capacity,codec,ledger,context,sourceTls,targetTls,transferUri,controller,cancelled);
                finish = chunks.finish(request.transferId(), context.bindingHash(), UUID.randomUUID());
            } finally { sends.shutdown(); }
            try (var lease = budget.reserve(peak)) {
                return finish(transferUri,context,sourceTls,targetTls,finish,ledger,cancelled);
            }
        } finally {
            try { if (!chunkerOwnsSource) source.close(); } finally { metrics.finish(); activeSource=null; active.set(false); synchronized (completion) { completion.notifyAll(); } }
        }
    }
    /** Recover facts after source-process loss. Null permits a fresh ID and a new source from offset zero. */
    public TransferJson.Status recover(URI endpoint, OpenRequest request, AcceptedTransfer accepted) throws IOException, InterruptedException {
        if (!active.compareAndSet(false,true)) throw new TransferException(BUSY);
        try (var lease = budget.reserve(4L*MetadataCodec.CONTROL_LIMIT)) {
            if (!"https".equals(endpoint.getScheme()) || !TransferHttpHandler.BASE.equals(endpoint.getRawPath())
                    || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || accepted!=null && !accepted.request().equals(request)) throw new IllegalArgumentException("Invalid recovery context");
            URI uri=URI.create(endpoint+"/"+request.transferId());
            var response=http.get(uri,request.targetNodeId(),request.routeId(),directory,MetadataCodec.CONTROL_LIMIT);
            if (response.status()==200) {
                var known=TransferJson.status(response.body());
                if (!known.transferId().equals(request.transferId())) throw new AuthenticationException();
                if (known.state()==State.COMPLETED) return known;
                if (known.state()==State.CANCELLED || known.state()==State.FAILED) return null;
                if (known.state()==State.RECOVERY_REQUIRED) throw new TransferException(UNKNOWN_COMMIT);
            }
            if (response.status()==404 && accepted!=null) throw new TransferException(UNKNOWN_COMMIT);
            if (response.status()==404 || accepted==null && response.status()==200 && TransferJson.status(response.body()).state()!=State.COMPLETED) {
                // Resolve an Open whose response never reached durable source storage before cancellation.
                var opened=http.exchange(endpoint,"POST","application/json",MetadataJson.encode(request),request.targetNodeId(),request.routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                success(opened);
                var parsed=MetadataJson.decode(opened.body(),OpenResponse.class);
                var context=BoundTransfer.freeze(request,parsed.accepted(),opened.source(),opened.target(),directory,clock.instant());
                if (!context.response().equals(parsed)) throw new AuthenticationException();
                accepted=context.stored();
                response=http.get(uri,request.targetNodeId(),request.routeId(),directory,MetadataCodec.CONTROL_LIMIT);
            }
            success(response); var status=TransferJson.status(response.body());
            if (!status.transferId().equals(request.transferId())) throw new AuthenticationException();
            if (status.state()==State.COMPLETED) return status;
            if (status.state()==State.CANCELLED || status.state()==State.FAILED) return null;
            if (status.state()==State.RECOVERY_REQUIRED || accepted==null) throw new TransferException(UNKNOWN_COMMIT);
            var context=BoundTransfer.restore(accepted,directory); context.checkWriter(response.source(),response.target());
            var command=new CancelCommand(request.transferId(),UUID.randomUUID(),accepted.response().bindingHash());
            response=http.exchange(URI.create(uri+"/cancel"),"POST","application/json",TransferJson.cancel(command),request.targetNodeId(),request.routeId(),directory,MetadataCodec.CONTROL_LIMIT);
            context.checkWriter(response.source(),response.target()); success(response); status=TransferJson.status(response.body());
            if (!status.transferId().equals(request.transferId())) throw new AuthenticationException();
            if (status.state()==State.COMPLETED) return status;
            if (status.state()!=State.CANCELLED) throw new TransferException(UNKNOWN_COMMIT);
            return null;
        } finally { active.set(false); synchronized (completion) { completion.notifyAll(); } }
    }
    private static final class Flight {
        final OrderedChunker.Pending pending;
        final PreparedChunk prepared;
        int attempts;
        Flight(OrderedChunker.Pending pending, PreparedChunk prepared) { this.pending = pending; this.prepared = prepared; }
    }
    private record Outcome(Flight flight, AuthenticatedHttpClient.Response response, IOException failure) {}
    private void live(BoundTransfer context, BooleanSupplier cancelled) throws TransferException {
        if (closed || cancelled.getAsBoolean()) throw new TransferException(UNAVAILABLE);
        if (!context.accepted().expiresAt().isAfter(clock.instant())) throw new TransferException(EXPIRED);
    }
    private void pipeline(ExecutorService sends, OrderedChunker chunks, int capacity, ChunkCompression codec,
                          ReceiptLedger ledger, BoundTransfer context, TlsIdentity source, TlsIdentity target, URI uri,
                          FeedbackController controller, BooleanSupplier cancelled) throws IOException, InterruptedException {
        var completed=new ExecutorCompletionService<Outcome>(sends);
        var flights=new java.util.ArrayList<Flight>(capacity);
        var futures=new java.util.HashMap<Future<Outcome>,Flight>();
        var policy=context.accepted().policy();
        boolean eof=false;
        try {
            while (!eof || !flights.isEmpty()) {
                live(context,cancelled);
                var parameters=controller==null ? new FeedbackController.Parameters((int)policy.initialChunkBytes(),
                        (int)policy.initialWindow(),policy.initialZstdLevel()) : controller.parameters();
                int window=Math.min(parameters.window(),capacity);
                metrics.window(window,window<parameters.window());
                // Observe already completed failures before preparing more input.
                Future<Outcome> done=completed.poll();
                if (done==null && !eof && flights.size()<window) {
                    OrderedChunker.Pending pending;
                    try { pending=chunks.next(parameters.chunkBytes()); }
                    catch (IllegalStateException e) { metrics.budgetFailure(); throw e; }
                    if (chunks.sourceExhausted()) { eof=true; metrics.sourceEof(); }
                    if (pending==null) { eof=true; metrics.sourceEof(); continue; }
                    try {
                        var prepared=new PreparedChunk(pending.chunk(),codec,parameters.zstdLevel());
                        metrics.prepared(pending.chunk().plainLength(),prepared.compressedLength(),prepared.compressionNanos());
                        ledger.assign(prepared.receipt(context.request().transferId()));
                        var flight=new Flight(pending,prepared); flights.add(flight); flight.attempts++;
                        futures.put(completed.submit(() -> send(flight,context,source,target,uri)),flight);
                    } catch (IOException | RuntimeException e) { pending.close(); throw e; }
                    continue;
                }
                if (done==null) {
                    if (flights.isEmpty()) continue;
                    done=completed.take();
                }
                Outcome outcome=result(done); futures.remove(done);
                if (outcome.failure!=null || outcome.response.status()<200 || outcome.response.status()>=300) {
                    var outcomes=new java.util.ArrayList<Outcome>(); outcomes.add(outcome);
                    while (!futures.isEmpty()) {
                        var future=completed.take(); outcomes.add(result(future)); futures.remove(future);
                    }
                    settle(sends,flights,ledger,context,source,target,uri,controller,cancelled,outcomes);
                    for (var flight : flights) flight.pending.close();
                    flights.clear();
                } else {
                    ledger.acknowledge(TransferJson.ack(outcome.response.body()).receipt());
                    metrics.confirmed(ledger.confirmedBytes());
                    outcome.flight.pending.close(); flights.remove(outcome.flight);
                }
                if (controller!=null) {
                    var round=metrics.pollRound(); if (round!=null) decide(controller,round,false);
                }
            }
        } finally {
            // Cancellation or a failed read cannot revoke buffers still owned by send workers.
            boolean interrupted=Thread.interrupted();
            for (var future : futures.keySet()) while (true) {
                try { future.get(); break; }
                catch (InterruptedException e) { interrupted=true; }
                catch (ExecutionException e) { break; }
            }
            for (var flight : flights) flight.pending.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static Outcome result(Future<Outcome> future) throws IOException, InterruptedException {
        try { return future.get(); }
        catch (ExecutionException e) { throw new IOException("Chunk worker failed",e.getCause()); }
    }
    private Outcome send(Flight flight, BoundTransfer context, TlsIdentity source, TlsIdentity target, URI uri)
            throws InterruptedException {
        UUID id=context.accepted().transferId();
        try {
            long sealStart = System.nanoTime();
            byte[] frame = HpkeFrames.sealChunk(context,key,source,target,flight.prepared.aad(id,context.bindingHash()),flight.prepared.compressedBytes());
            metrics.sealed(System.nanoTime() - sealStart);
            metrics.beginAttempt(flight.attempts > 1);
            try {
                metrics.sent(frame.length,flight.attempts > 1);
                long sent = System.nanoTime();
                var response = http.exchange(URI.create(uri + "/chunks/" + flight.prepared.receipt(id).chunkIndex()),"PUT","application/lcp-frame",frame,
                        context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                context.checkWriter(response.source(),response.target());
                try { success(response); }
                catch (TransferException e) {
                    if (e.code() == BUSY) metrics.busy();
                    return new Outcome(flight,response,null);
                }
                var ack = TransferJson.ack(response.body());
                if (!flight.prepared.receipt(id).equals(ack.receipt())) throw new AuthenticationException();
                metrics.acknowledged(System.nanoTime() - sent, ack.queueMicros(), ack.persistMicros(),
                        flight.attempts > 1, !"APPLIED".equals(ack.disposition()));
                return new Outcome(flight,response,null);
            } finally { metrics.endAttempt(); }
        } catch (IOException e) { return new Outcome(flight,null,e); }
    }
    private void settle(ExecutorService sends, java.util.List<Flight> batch, ReceiptLedger ledger, BoundTransfer context,
                        TlsIdentity source, TlsIdentity target, URI uri, FeedbackController controller, BooleanSupplier cancelled, java.util.List<Outcome> initial) throws IOException, InterruptedException {
        UUID id = context.accepted().transferId();
        while (batch.stream().anyMatch(f -> !ledger.acknowledged(f.prepared.receipt(id).chunkIndex()))) {
            live(context,cancelled);
            int requestedWindow = controller == null ? (int)context.accepted().policy().initialWindow() : controller.parameters().window();
            int effectiveWindow = (int)Math.min(requestedWindow, budget.capacity() / CompressionPlan.peak(context.accepted().limits(),
                    CompressionPlan.select(context.accepted().compressionCode(),optionalCodec)));
            metrics.window(effectiveWindow, effectiveWindow < requestedWindow);
            var admission = new Semaphore(effectiveWindow);
            var futures = new java.util.ArrayList<Future<Outcome>>(batch.size());
            var outcomes = new java.util.ArrayList<Outcome>(batch.size());
            if (initial!=null) { outcomes.addAll(initial); initial=null; } else {
                try {
                    for (var flight : batch) {
                        if (ledger.acknowledged(flight.prepared.receipt(id).chunkIndex())) continue;
                        flight.attempts++;
                        futures.add(sends.submit(() -> {
                            admission.acquire();
                            try {
                                return send(flight,context,source,target,uri);
                            }
                            finally { admission.release(); }
                        }));
                    }
                    for (var future : futures) {
                        try { outcomes.add(future.get()); }
                        catch (ExecutionException e) { throw new IOException("Chunk worker failed",e.getCause()); }
                    }
                } finally {
                    // Never use Future.cancel as proof that a worker has relinquished its buffers.
                    boolean interrupted = Thread.interrupted();
                    for (var future : futures) while (true) {
                        try { future.get(); break; }
                        catch (InterruptedException e) { interrupted = true; }
                        catch (ExecutionException e) { break; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            boolean uncertain = false, pressure = false; long retryAfter = 0; int attempts = 0;
            for (var outcome : outcomes) {
                attempts = Math.max(attempts,outcome.flight.attempts);
                if (outcome.failure != null) { retryable(outcome.failure); uncertain = true; continue; }
                var response = outcome.response;
                context.checkWriter(response.source(),response.target());
                try { success(response); }
                catch (IOException e) { retryable(e); pressure |= e instanceof TransferException t && t.code() == BUSY; uncertain = true; retryAfter = Math.max(retryAfter,response.retryAfterMillis()); continue; }
                Receipt expected = outcome.flight.prepared.receipt(id);
                Receipt received = TransferJson.ack(response.body()).receipt();
                if (!expected.equals(received)) throw new AuthenticationException();
                ledger.acknowledge(received);
                metrics.confirmed(ledger.confirmedBytes());
            }
            if (pressure && controller != null) { decide(controller,null,true); metrics.restartRound(); }
            if (!uncertain) return;
            live(context,cancelled);
            reconcile(uri,context,ledger);
            boolean unresolved = batch.stream().anyMatch(f -> !ledger.acknowledged(f.prepared.receipt(id).chunkIndex()));
            if (!unresolved) return;
            if (attempts >= RetryPolicy.MAX_ATTEMPTS) {
                // The final reconciliation still found absent blocks. Cancellation is bounded and best effort.
                try {
                    var cancel = new CancelCommand(id,UUID.randomUUID(),context.bindingHash());
                    var response = http.exchange(URI.create(uri + "/cancel"),"POST","application/json",TransferJson.cancel(cancel),
                            context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                    context.checkWriter(response.source(),response.target()); success(response);
                } catch (IOException ignored) { /* Unconfirmed cancellation never permits reuse of this transfer ID. */ }
                throw new TransferException(UNKNOWN_COMMIT);
            }
            retries.pause(attempts,context.accepted().expiresAt(),retryAfter);
        }
    }
    private void decide(FeedbackController controller, TransferMetrics.Round round, boolean pressure) {
        boolean trial = controller.trialActive(); var old = controller.parameters(); long start = System.nanoTime();
        if (pressure) controller.pressure(); else controller.observe(round);
        metrics.decision(!trial && controller.trialActive(), trial && !controller.trialActive()
                && (pressure || !old.equals(controller.parameters())), System.nanoTime()-start);
    }
    private static void retryable(IOException failure) throws IOException {
        if (failure instanceof TransferException transfer && transfer.code() != BUSY && transfer.code() != UNAVAILABLE) throw failure;
    }
    private AuthenticatedHttpClient.Response query(URI uri, BoundTransfer context) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            long retryAfter = 0;
            try {
                var response = http.get(uri,context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                context.checkWriter(response.source(),response.target()); retryAfter = response.retryAfterMillis(); success(response); return response;
            } catch (IOException failure) {
                retryable(failure);
                retries.pause(attempt,clock.instant().plusSeconds(60),retryAfter);
            }
        }
    }
    private TransferJson.Status status(URI uri, BoundTransfer context) throws IOException, InterruptedException {
        TransferJson.Status status;
        try { status = TransferJson.status(query(uri,context).body()); }
        catch (TransferException e) { if (e.code() == NOT_FOUND) throw new TransferException(UNKNOWN_COMMIT); throw e; }
        if (!status.transferId().equals(context.accepted().transferId()) || !status.expiresAt().equals(context.accepted().expiresAt())) throw new AuthenticationException();
        if (status.state() == State.FAILED || status.state() == State.CANCELLED || status.state() == State.RECOVERY_REQUIRED)
            throw new TransferException(UNKNOWN_COMMIT);
        return status;
    }
    private void reconcile(URI uri, BoundTransfer context, ReceiptLedger ledger) throws IOException, InterruptedException {
        status(uri,context);
        long revision = 0; // Only receipt-page revisions share this counter.
        for (long from = 0; from < ledger.assigned();) {
            int limit = (int)Math.min(256,ledger.assigned()-from);
            var page = TransferJson.page(query(URI.create(uri + "/receipts?from=" + from + "&limit=" + limit),context).body());
            ledger.reconcile(page,from,limit,revision); metrics.confirmed(ledger.confirmedBytes()); revision = page.revision(); from += limit;
        }
    }
    private VerifiedResult finish(URI uri, BoundTransfer context, TlsIdentity source, TlsIdentity target,
                                  FinishManifest manifest, ReceiptLedger ledger, BooleanSupplier cancelled) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            live(context,cancelled); long retryAfter = 0;
            try {
                byte[] frame = HpkeFrames.sealFinish(context,key,source,target,manifest);
                var response = http.exchange(URI.create(uri + "/finish"),"POST","application/lcp-frame",frame,
                        context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                context.checkWriter(response.source(),response.target()); retryAfter = response.retryAfterMillis(); success(response);
                var status = TransferJson.status(response.body());
                if (status.state() == State.COMPLETED) return completed(status,context,manifest);
                if (response.status() != 202 || status.state() != State.VERIFYING) throw new AuthenticationException();
            } catch (IOException failure) {
                if (failure instanceof TransferException transfer && transfer.code() == MISSING_CHUNKS) {
                    reconcile(uri,context,ledger);
                    throw new TransferException(UNKNOWN_COMMIT);
                }
                retryable(failure);
            }
            var status = status(uri,context);
            if (status.state() == State.COMPLETED) return completed(status,context,manifest);
            long verificationStarted = System.nanoTime();
            while (status.state() == State.VERIFYING) {
                live(context,cancelled);
                if (System.nanoTime()-verificationStarted >= Duration.ofMinutes(10).toNanos()) throw new TransferException(UNKNOWN_COMMIT);
                Thread.sleep(500);
                status = status(uri,context);
                if (status.state() == State.COMPLETED) return completed(status,context,manifest);
            }
            reconcile(uri,context,ledger);
            retries.pause(attempt,context.accepted().expiresAt(),retryAfter);
        }
    }
    private static VerifiedResult completed(TransferJson.Status status, BoundTransfer context, FinishManifest finish) throws AuthenticationException {
        var result = status.result(); var view = status.finish();
        if (status.state() != State.COMPLETED || !status.transferId().equals(finish.transferId()) || view == null || result == null
                || !status.expiresAt().equals(context.accepted().expiresAt())
                || view.totalChunks() != finish.totalChunks() || view.totalPlainBytes() != finish.totalPlainBytes() || !view.root().equals(finish.root())
                || status.committedChunks() != finish.totalChunks() || status.committedPlainBytes() != finish.totalPlainBytes()
                || !view.commandId().equals(finish.commandId()) || !view.manifestHash().equals(MetadataCodec.manifestHash(finish))
                || !result.handleId().equals(context.accepted().handleId()) || result.totalChunks() != finish.totalChunks()
                || result.totalPlainBytes() != finish.totalPlainBytes() || !result.root().equals(finish.root())
                || !result.manifestHash().equals(MetadataCodec.manifestHash(finish))) throw new AuthenticationException();
        return result;
    }
    private static void success(AuthenticatedHttpClient.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) {
            if (response.body().length == 0) throw new AuthenticationException();
            ErrorCode error = TransferJson.error(response.body());
            if (TransferJson.httpStatus(error) != response.status()) throw new AuthenticationException();
            throw new TransferException(error);
        }
    }
    /** Close after outstanding transfers complete. Transport and Sink lifetimes remain caller-owned. */
    @Override public void close() throws IOException { close(Duration.ofSeconds(60)); }
    /** A timeout leaves outstanding workers and their leases owned until they really exit. */
    public void close(Duration grace) throws IOException {
        if (grace.isNegative() || grace.isZero()) throw new IllegalArgumentException("Invalid shutdown grace");
        long started=System.nanoTime(); closed=true; worker.shutdown();
        var source=activeSource; if (source!=null) source.close();
        try {
            synchronized (completion) {
                while (active.get()) {
                    long remaining=grace.toNanos()-(System.nanoTime()-started);
                    if (remaining<=0) throw new TransferException(UNKNOWN_COMMIT);
                    TimeUnit.NANOSECONDS.timedWait(completion,remaining);
                }
            }
            if (!worker.awaitTermination(Math.max(0,grace.toNanos()-(System.nanoTime()-started)),TimeUnit.NANOSECONDS)) throw new TransferException(UNKNOWN_COMMIT);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TransferException(UNKNOWN_COMMIT); }
    }
}
