package io.github.aullchen.lcp.security;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import java.time.Instant;

/** Frozen authenticated Open context; persistence and replay registration belong to the Sink. */
public final class BoundTransfer {
    private final OpenRequest request;
    private final Accepted accepted;
    private final Bytes32 sourceTls, targetTls, binding;
    private final PeerDirectory directory;
    private BoundTransfer(OpenRequest request, Accepted accepted, TlsIdentity source, TlsIdentity target, PeerDirectory directory) {
        this.request = request; this.accepted = accepted; this.directory = directory;
        sourceTls = source.spkiHash(); targetTls = target.spkiHash();
        binding = MetadataCodec.bindingHash(request, accepted, sourceTls, targetTls);
    }
    public static BoundTransfer freeze(OpenRequest request, Accepted accepted, TlsIdentity source,
                                        TlsIdentity target, PeerDirectory directory, Instant now) {
        if (!request.sourceNodeId().equals(source.nodeId()) || !request.targetNodeId().equals(target.nodeId())
                || !request.transferId().equals(accepted.transferId())
                || !request.compressionOffers().contains(accepted.compressionCode())
                || !request.expiresAt().isAfter(now) || !accepted.expiresAt().isAfter(now)
                || accepted.expiresAt().isAfter(request.expiresAt()) || request.createdAt().isAfter(now.plusSeconds(120)))
            throw new AuthenticationException();
        Limits r = request.limits(), a = accepted.limits();
        if (a.maxFrameBytes() > r.maxFrameBytes() || a.maxPlainBytes() > r.maxPlainBytes()
                || a.maxChunks() > r.maxChunks() || a.maxTransferBytes() > r.maxTransferBytes()
                || a.maxInFlightChunks() > r.maxInFlightChunks()) throw new AuthenticationException();
        Policy rp = request.policy(), ap = accepted.policy();
        if (rp.mode() != ap.mode() || rp.policyVersion() != ap.policyVersion()) throw new AuthenticationException();
        interval(rp.minChunkBytes(), rp.initialChunkBytes(), rp.maxChunkBytes(), ap.minChunkBytes(), ap.initialChunkBytes(), ap.maxChunkBytes());
        interval(rp.minWindow(), rp.initialWindow(), rp.maxWindow(), ap.minWindow(), ap.initialWindow(), ap.maxWindow());
        if (accepted.compressionCode() == 1) interval(rp.minZstdLevel(), rp.initialZstdLevel(), rp.maxZstdLevel(), ap.minZstdLevel(), ap.initialZstdLevel(), ap.maxZstdLevel());
        var result = new BoundTransfer(request, accepted, source, target, directory);
        result.checkWriter(source, target); return result;
    }
    private static void interval(long min, long initial, long max, long amin, long ainitial, long amax) {
        if (amin < min || amax > max || ainitial != Math.max(amin, Math.min(initial, amax))) throw new AuthenticationException();
    }
    /** Restore trusted durable facts without granting a new lifetime or changing the binding. */
    public static BoundTransfer restore(io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer stored, PeerDirectory directory) {
        io.github.aullchen.lcp.core.protocol.StorageCodec.validate(stored);
        return new BoundTransfer(stored, directory);
    }
    private BoundTransfer(io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer stored, PeerDirectory directory) {
        request = stored.request(); accepted = stored.response().accepted(); this.directory = directory;
        sourceTls = stored.sourceTlsSpki(); targetTls = stored.targetTlsSpki(); binding = stored.response().bindingHash();
    }
    public io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer stored() {
        return new io.github.aullchen.lcp.api.TransferStorage.AcceptedTransfer(request, response(), sourceTls, targetTls);
    }
    public OpenRequest request() { return request; }
    public Accepted accepted() { return accepted; }
    public Bytes32 bindingHash() { return binding; }
    public OpenResponse response() { return new OpenResponse(accepted, MetadataCodec.openRequestHash(request), binding); }
    public void checkWriter(TlsIdentity source, TlsIdentity target) {
        if (!request.sourceNodeId().equals(source.nodeId()) || !request.targetNodeId().equals(target.nodeId())
                || !sourceTls.equals(source.spkiHash()) || !targetTls.equals(target.spkiHash())) throw new AuthenticationException();
        requireKeys();
    }
    /** A currently authorized node may query using a new certificate, but cannot resume writing. */
    public void checkReader(TlsIdentity source) {
        if (!request.sourceNodeId().equals(source.nodeId())) throw new AuthenticationException();
        directory.authorizeRoute(source.nodeId(), request.routeId());
    }
    void requireKeys() {
        sourceKey(); targetKey();
    }
    org.bouncycastle.crypto.params.X25519PublicKeyParameters sourceKey() {
        return directory.require(request.sourceNodeId(), request.routeId(), request.sourceKeyId(), request.sourcePublicKeyHash());
    }
    org.bouncycastle.crypto.params.X25519PublicKeyParameters targetKey() {
        return directory.require(request.targetNodeId(), request.routeId(), request.targetKeyId(), request.targetPublicKeyHash());
    }
}
