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

/** Bounded-window fixed-policy sender. A failed or uncertain exchange is surfaced without automatic retry. */
public final class FixedTransferClient implements AutoCloseable {
    private final AuthenticatedHttpClient http;
    private final HpkeKey key;
    private final PeerDirectory directory;
    private final ByteBudget budget;
    private final Clock clock;
    private final ChunkCompression optionalCodec;
    private final BoundedExecutor worker = new BoundedExecutor(1, 1, "lcp-source");
    private final AtomicBoolean active = new AtomicBoolean();
    public FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock) {
        this(http, key, directory, budget, clock, ChunkCompression.NONE);
    }
    public FixedTransferClient(AuthenticatedHttpClient http, HpkeKey key, PeerDirectory directory, ByteBudget budget, Clock clock, ChunkCompression optionalCodec) {
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
            FinishManifest finish;
            int window = (int) Math.min(context.accepted().policy().initialWindow(), budget.capacity() / peak);
            var sends = Executors.newFixedThreadPool(window, r -> { var t = new Thread(r, "lcp-chunk"); t.setDaemon(true); return t; });
            try (var chunks = new OrderedChunker(source, Math.toIntExact(context.accepted().policy().initialChunkBytes()),
                    context.accepted().limits(), budget, peak, Duration.ofSeconds(10), window)) {
                chunkerOwnsSource = true;
                boolean eof = false;
                while (!eof) {
                    var batch = new java.util.ArrayList<Future<Void>>(window);
                    try {
                        for (int i = 0; i < window; i++) {
                            var pending = chunks.next();
                            if (pending == null) { eof = true; break; }
                            try {
                                var prepared = new PreparedChunk(pending.chunk(), codec, context.accepted().policy().initialZstdLevel());
                                batch.add(sends.submit(() -> {
                                    try (var owned = pending) {
                                        byte[] frame = HpkeFrames.sealChunk(context, key, sourceTls, targetTls,
                                                prepared.aad(request.transferId(), context.bindingHash()), prepared.compressedBytes());
                                        var response = http.exchange(URI.create(transferUri + "/chunks/" + prepared.receipt(request.transferId()).chunkIndex()), "PUT", "application/lcp-frame", frame,
                                                request.targetNodeId(), request.routeId(), directory, MetadataCodec.CONTROL_LIMIT);
                                        context.checkWriter(response.source(), response.target()); success(response);
                                        if (!TransferJson.ack(response.body()).receipt().equals(prepared.receipt(request.transferId()))) throw new AuthenticationException();
                                        return null;
                                    }
                                }));
                            } catch (RuntimeException e) { pending.close(); throw e; }
                        }
                        for (var sent : batch) await(sent);
                    } finally {
                        // Interruption stops new reads, but cannot revoke ownership of running workers.
                        boolean interrupted = Thread.interrupted();
                        for (var sent : batch) {
                            while (true) {
                                try { sent.get(); break; }
                                catch (InterruptedException e) { interrupted = true; }
                                catch (ExecutionException e) { break; }
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
                finish = chunks.finish(request.transferId(), context.bindingHash(), UUID.randomUUID());
            } finally { sends.shutdown(); }
            try (var lease = budget.reserve(peak)) {
                byte[] frame = HpkeFrames.sealFinish(context, key, sourceTls, targetTls, finish);
                var response = http.exchange(URI.create(transferUri + "/finish"), "POST", "application/lcp-frame", frame,
                        request.targetNodeId(), request.routeId(), directory, MetadataCodec.CONTROL_LIMIT);
                context.checkWriter(response.source(), response.target()); success(response);
                var status = TransferJson.status(response.body()); var result = status.result(); var view = status.finish();
                if (status.state() != State.COMPLETED || !status.transferId().equals(request.transferId()) || view == null || result == null
                        || !view.commandId().equals(finish.commandId()) || !view.manifestHash().equals(MetadataCodec.manifestHash(finish))
                        || !result.handleId().equals(context.accepted().handleId()) || result.totalChunks() != finish.totalChunks()
                        || result.totalPlainBytes() != finish.totalPlainBytes() || !result.root().equals(finish.root())
                        || !result.manifestHash().equals(MetadataCodec.manifestHash(finish))) throw new AuthenticationException();
                return result;
            }
        } finally {
            try { if (!chunkerOwnsSource) source.close(); } finally { active.set(false); }
        }
    }
    private static void await(Future<Void> sent) throws IOException, InterruptedException {
        try { sent.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IOException failure) throw failure;
            if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new IOException("Chunk worker failed", e.getCause());
        }
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
