package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {
    static Certificates.Identity ca, sourceCert, targetCert;
    static HpkeKey sourceKey, targetKey;
    static final Bytes32 ZERO = new Bytes32(new byte[32]);
    static final UUID ID = UUID.randomUUID();
    static final Limits LIMITS = new Limits(10000, 1024, 100, 10000, 1);
    static final Policy POLICY = new Policy(0, 1, 512, 512, 512, 1, 1, 1, 1, 1, 1);
    @BeforeAll static void certificates() throws Exception {
        ca = Certificates.ca(); sourceCert = Certificates.leaf(ca, "source", true); targetCert = Certificates.leaf(ca, "target", false);
        sourceKey = HpkeKey.generate(); targetKey = HpkeKey.generate();
    }
    static PeerDirectory directory() {
        return new PeerDirectory(List.of(new PeerDirectory.Entry("source", "source-key", Set.of("demo"), sourceKey.publicSpki()), new PeerDirectory.Entry("target", "target-key", Set.of("demo"), targetKey.publicSpki())));
    }
    static OpenRequest request() { return new OpenRequest(ID, "demo", "source", "target", ZERO, "source-key", sourceKey.publicHash(), "target-key", targetKey.publicHash(), List.of(0), POLICY, LIMITS, Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); }
    static Accepted accepted(OpenRequest request) { return new Accepted(ID, ZERO, "handle", 0, POLICY, LIMITS, request.expiresAt()); }
    static TlsIdentity source() { return TlsIdentity.certificate(sourceCert.certificate(), TlsIdentity.Role.CLIENT); }
    static TlsIdentity target() { return TlsIdentity.certificate(targetCert.certificate(), TlsIdentity.Role.SERVER); }
    static BoundTransfer bound(PeerDirectory directory) { var r = request(); return BoundTransfer.freeze(r, accepted(r), source(), target(), directory, Instant.now()); }
    static ChunkAad aad(BoundTransfer b) { return new ChunkAad(ID, b.bindingHash(), 0, 0, 3, 3, 0); }
    @Test void chunkAndFinishUseAuthenticatedProjectContext() {
        var b = bound(directory()); var wire = HpkeFrames.sealChunk(b, sourceKey, source(), target(), aad(b), new byte[]{0,1,2});
        assertArrayEquals(new byte[]{0,1,2}, HpkeFrames.openChunk(b, targetKey, source(), target(), ID, 0, ByteBuffer.wrap(wire)));
        var finish = new FinishManifest(ID, b.bindingHash(), UUID.randomUUID(), 1, 3, MetadataCodec.hash(new byte[]{0}));
        var finalWire = HpkeFrames.sealFinish(b, sourceKey, source(), target(), finish);
        assertEquals(finish, HpkeFrames.openFinish(b, targetKey, source(), target(), ID, ByteBuffer.wrap(finalWire)));
        assertThrows(AuthenticationException.class, () -> HpkeFrames.openChunk(b, targetKey, source(), target(), UUID.randomUUID(), 0, ByteBuffer.wrap(wire)));
        assertThrows(AuthenticationException.class, () -> HpkeFrames.openChunk(b, targetKey, source(), target(), ID, 1, ByteBuffer.wrap(wire)));
        assertThrows(AuthenticationException.class, () -> HpkeFrames.openFinish(b, targetKey, source(), target(), UUID.randomUUID(), ByteBuffer.wrap(finalWire)));
        assertThrows(AuthenticationException.class, () -> HpkeFrames.openChunk(b, HpkeKey.generate(), source(), target(), ID, 0, ByteBuffer.wrap(wire)));
    }
    @ParameterizedTest @ValueSource(strings = {"domain", "transfer", "binding", "index", "offset", "plain", "compressed", "codec", "enc", "cipher", "type"})
    void rejectsEveryChunkAadAndEnvelopeMutation(String field) {
        var b = bound(directory()); var wire = HpkeFrames.sealChunk(b, sourceKey, source(), target(), aad(b), new byte[]{0,1,2});
        var frame = FrameCodec.decode(ByteBuffer.wrap(wire), LIMITS); byte[] a = bytes(frame.aad());
        int last = a.length - 1;
        switch (field) {
            case "domain" -> a[2] ^= 1;
            case "transfer" -> a[20] ^= 1;
            case "binding" -> a[last - 6] ^= 1;
            case "index" -> a[last - 4] = 1;
            case "offset" -> a[last - 3] = 1;
            case "plain" -> a[last - 2] = 2;
            case "compressed" -> a[last - 1] = 2;
            case "codec" -> a[last] = 1;
            case "enc" -> wire[16 + a.length] ^= 1;
            case "cipher" -> wire[wire.length - 1] ^= 1;
            case "type" -> wire[5] = 1;
        }
        if (!Set.of("enc", "cipher", "type").contains(field)) System.arraycopy(a, 0, wire, 16, a.length);
        var error = assertThrows(RuntimeException.class, () -> HpkeFrames.openChunk(b, targetKey, source(), target(), ID, 0, ByteBuffer.wrap(wire)));
        assertTrue(error instanceof AuthenticationException || error instanceof ProtocolException);
    }
    static byte[] bytes(ByteBuffer b) { var a = new byte[b.remaining()]; b.get(a); return a; }
    @Test void revokedHpkeAndRotatedTlsKeysCannotWriteButAuthorizedNodeCanRead() throws Exception {
        var dir = directory(); var b = bound(dir);
        var rotated = TlsIdentity.certificate(Certificates.leaf(ca, "source", true).certificate(), TlsIdentity.Role.CLIENT);
        b.checkReader(rotated);
        assertThrows(AuthenticationException.class, () -> b.checkWriter(rotated, target()));
        dir.revoke("source", "source-key");
        assertThrows(AuthenticationException.class, () -> b.checkWriter(source(), target()));
        assertThrows(AuthenticationException.class, () -> b.checkReader(source()));
    }
    @ParameterizedTest @ValueSource(strings = {"routeId", "sourceNodeId", "targetNodeId", "sourceKeyId", "targetKeyId", "sourcePublicKeyHash", "targetPublicKeyHash"})
    void rejectsUnknownIdentityBindings(String field) {
        var r = request(); String json = new String(MetadataJson.encode(r), java.nio.charset.StandardCharsets.UTF_8);
        String replacement = field.endsWith("Hash") ? Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]) : "unknown";
        json = json.replaceAll("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\"" + replacement + "\"");
        var changed = MetadataJson.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), OpenRequest.class);
        assertThrows(AuthenticationException.class, () -> BoundTransfer.freeze(changed, accepted(changed), source(), target(), directory(), Instant.now()));
    }
    @Test void expiredOrFutureOpenAndExpandedLimitsAreRejected() {
        var r = request();
        assertThrows(AuthenticationException.class, () -> BoundTransfer.freeze(r, accepted(r), source(), target(), directory(), r.expiresAt()));
        assertThrows(AuthenticationException.class, () -> BoundTransfer.freeze(r, accepted(r), source(), target(), directory(), r.createdAt().minusSeconds(121)));
        var accepted = new Accepted(ID, ZERO, "handle", 0, POLICY, new Limits(10001, 1024, 100, 10000, 1), r.expiresAt());
        assertThrows(AuthenticationException.class, () -> BoundTransfer.freeze(r, accepted, source(), target(), directory(), Instant.now()));
    }
    @ParameterizedTest @ValueSource(strings = {"transfer", "binding", "command", "cipher"})
    void rejectsFinishAuthenticationChanges(String field) {
        var b = bound(directory());
        var manifest = new FinishManifest(ID, b.bindingHash(), UUID.randomUUID(), 0, 0, MetadataCodec.hash(new byte[0]));
        var wire = HpkeFrames.sealFinish(b, sourceKey, source(), target(), manifest);
        var frame = FrameCodec.decode(ByteBuffer.wrap(wire), LIMITS);
        var aad = MetadataCodec.decode(bytes(frame.aad()), FinishAad.class);
        byte[] changed = switch (field) {
            case "transfer" -> MetadataCodec.encode(new FinishAad(UUID.randomUUID(), aad.bindingHash(), aad.commandId()));
            case "binding" -> MetadataCodec.encode(new FinishAad(aad.transferId(), ZERO, aad.commandId()));
            case "command" -> MetadataCodec.encode(new FinishAad(aad.transferId(), aad.bindingHash(), UUID.randomUUID()));
            default -> bytes(frame.aad());
        };
        System.arraycopy(changed, 0, wire, 16, changed.length);
        if (field.equals("cipher")) wire[wire.length - 1] ^= 1;
        assertThrows(AuthenticationException.class, () -> HpkeFrames.openFinish(b, targetKey, source(), target(), ID, ByteBuffer.wrap(wire)));
    }
    @Test void replacementHpkeAuthorizationAllowsOnlyReadOfOldContext() {
        var replacement = HpkeKey.generate();
        var dir = new PeerDirectory(List.of(
                new PeerDirectory.Entry("source", "source-key", Set.of("demo"), sourceKey.publicSpki()),
                new PeerDirectory.Entry("source", "replacement", Set.of("demo"), replacement.publicSpki()),
                new PeerDirectory.Entry("target", "target-key", Set.of("demo"), targetKey.publicSpki())));
        var old = bound(dir); dir.revoke("source", "source-key");
        old.checkReader(source());
        assertThrows(AuthenticationException.class, () -> old.checkWriter(source(), target()));
    }
}
