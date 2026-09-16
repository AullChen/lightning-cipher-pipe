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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.*;

/** Bounded-window fixed-policy sender. Uncertain writes are reconciled before bounded retries. */
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
        return CompletableFuture.supplyAsync(() -> {
            try { return transfer(endpoint, request, source); }
            catch (IOException e) { throw new CompletionException(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CompletionException(e); }
        }, worker);
    }
    public VerifiedResult transfer(URI endpoint, OpenRequest request, TransferSource source) throws IOException, InterruptedException {
        if (!active.compareAndSet(false, true)) { source.close(); throw new TransferException(BUSY); }
        boolean chunkerOwnsSource = false;
        try {
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !TransferHttpHandler.BASE.equals(endpoint.getRawPath()) || request.policy().mode() != 0)
                throw new IllegalArgumentException("Expected fixed transfer HTTPS endpoint");
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
            ChunkCompression codec = CompressionPlan.select(context.accepted().compressionCode(), optionalCodec);
            CompressionPlan.validate(context.accepted().policy(), context.accepted().limits(), codec, budget.capacity());
            long peak = CompressionPlan.peak(context.accepted().limits(), codec);
            URI transferUri = URI.create(endpoint.toString() + "/" + request.transferId());
            var ledger = new ReceiptLedger(request.transferId(),context.accepted().limits().maxChunks(),metadataBudget);
            FinishManifest finish;
            int window = (int) Math.min(context.accepted().policy().initialWindow(), budget.capacity() / peak);
            var sends = new BoundedExecutor(window,window,"lcp-chunk");
            try (var chunks = new OrderedChunker(source, Math.toIntExact(context.accepted().policy().initialChunkBytes()),
                    context.accepted().limits(), budget, peak, Duration.ofSeconds(10), window)) {
                chunkerOwnsSource = true;
                boolean eof = false;
                while (!eof) {
                    var batch = new java.util.ArrayList<Flight>(window);
                    try {
                        for (int i = 0; i < window; i++) {
                            var pending = chunks.next();
                            if (pending == null) { eof = true; break; }
                            try {
                                var prepared = new PreparedChunk(pending.chunk(), codec, context.accepted().policy().initialZstdLevel());
                                ledger.assign(prepared.receipt(request.transferId()));
                                batch.add(new Flight(pending,prepared));
                            } catch (IOException | RuntimeException e) { pending.close(); throw e; }
                        }
                        settle(sends,batch,ledger,context,sourceTls,targetTls,transferUri);
                    } finally { for (var flight : batch) flight.pending.close(); }
                }
                finish = chunks.finish(request.transferId(), context.bindingHash(), UUID.randomUUID());
            } finally { sends.shutdown(); }
            try (var lease = budget.reserve(peak)) {
                return finish(transferUri,context,sourceTls,targetTls,finish,ledger);
            }
        } finally {
            try { if (!chunkerOwnsSource) source.close(); } finally { active.set(false); }
        }
    }
    private static final class Flight {
        final OrderedChunker.Pending pending;
        final PreparedChunk prepared;
        int attempts;
        Flight(OrderedChunker.Pending pending, PreparedChunk prepared) { this.pending = pending; this.prepared = prepared; }
    }
    private record Outcome(Flight flight, AuthenticatedHttpClient.Response response, IOException failure) {}
    private void live(BoundTransfer context) throws TransferException {
        if (!context.accepted().expiresAt().isAfter(clock.instant())) throw new TransferException(EXPIRED);
    }
    private void settle(ExecutorService sends, java.util.List<Flight> batch, ReceiptLedger ledger, BoundTransfer context,
                        TlsIdentity source, TlsIdentity target, URI uri) throws IOException, InterruptedException {
        UUID id = context.accepted().transferId();
        while (batch.stream().anyMatch(f -> !ledger.acknowledged(f.prepared.receipt(id).chunkIndex()))) {
            live(context);
            var futures = new java.util.ArrayList<Future<Outcome>>(batch.size());
            var outcomes = new java.util.ArrayList<Outcome>(batch.size());
            try {
                for (var flight : batch) {
                    if (ledger.acknowledged(flight.prepared.receipt(id).chunkIndex())) continue;
                    flight.attempts++;
                    futures.add(sends.submit(() -> {
                        try {
                            byte[] frame = HpkeFrames.sealChunk(context,key,source,target,flight.prepared.aad(id,context.bindingHash()),flight.prepared.compressedBytes());
                            var response = http.exchange(URI.create(uri + "/chunks/" + flight.prepared.receipt(id).chunkIndex()),"PUT","application/lcp-frame",frame,
                                    context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                            return new Outcome(flight,response,null);
                        } catch (IOException e) { return new Outcome(flight,null,e); }
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
            boolean uncertain = false; long retryAfter = 0; int attempts = 0;
            for (var outcome : outcomes) {
                attempts = Math.max(attempts,outcome.flight.attempts);
                if (outcome.failure != null) { retryable(outcome.failure); uncertain = true; continue; }
                var response = outcome.response;
                context.checkWriter(response.source(),response.target());
                try { success(response); }
                catch (IOException e) { retryable(e); uncertain = true; retryAfter = Math.max(retryAfter,response.retryAfterMillis()); continue; }
                Receipt expected = outcome.flight.prepared.receipt(id);
                Receipt received = TransferJson.ack(response.body()).receipt();
                if (!expected.equals(received)) throw new AuthenticationException();
                ledger.acknowledge(received);
            }
            if (!uncertain) return;
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
    private static void retryable(IOException failure) throws IOException {
        if (failure instanceof TransferException transfer && transfer.code() != BUSY && transfer.code() != UNAVAILABLE) throw failure;
    }
    private AuthenticatedHttpClient.Response query(URI uri, BoundTransfer context) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            live(context); long retryAfter = 0;
            try {
                var response = http.get(uri,context.request().targetNodeId(),context.request().routeId(),directory,MetadataCodec.CONTROL_LIMIT);
                context.checkWriter(response.source(),response.target()); retryAfter = response.retryAfterMillis(); success(response); return response;
            } catch (IOException failure) {
                retryable(failure);
                retries.pause(attempt,context.accepted().expiresAt(),retryAfter);
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
        long revision = status(uri,context).revision();
        for (long from = 0; from < ledger.assigned();) {
            int limit = (int)Math.min(256,ledger.assigned()-from);
            var page = TransferJson.page(query(URI.create(uri + "/receipts?from=" + from + "&limit=" + limit),context).body());
            ledger.reconcile(page,from,limit,revision); revision = page.revision(); from += limit;
        }
    }
    private VerifiedResult finish(URI uri, BoundTransfer context, TlsIdentity source, TlsIdentity target,
                                  FinishManifest manifest, ReceiptLedger ledger) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            live(context); long retryAfter = 0;
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
            for (int poll = 1; status.state() == State.VERIFYING; poll++) {
                retries.pause(poll,context.accepted().expiresAt(),retryAfter);
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
    @Override public void close() { worker.close(); }
}
