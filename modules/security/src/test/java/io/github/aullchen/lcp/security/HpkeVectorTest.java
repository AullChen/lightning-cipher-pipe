package io.github.aullchen.lcp.security;

import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HpkeVectorTest {
    static final Properties VECTOR = load();
    static Properties load() {
        var p = new Properties();
        try (var in = HpkeVectorTest.class.getResourceAsStream("/hpke-rfc9180.properties")) { p.load(in); }
        catch (Exception e) { throw new AssertionError(e); }
        return p;
    }
    static byte[] v(String name) { return HexFormat.of().parseHex(VECTOR.getProperty(name)); }
    @Test void decryptsRfc9180AuthVectorAndUsesFreshEncapsulation() {
        var recipient = HpkeKey.raw(v("skRm"));
        var source = new X25519PublicKeyParameters(v("pkSm"));
        assertArrayEquals(v("pt"), HpkeFrames.open(recipient, source, v("info"), v("aad"), v("enc"), v("ct")));
        var sender = HpkeKey.raw(v("skSm")); var target = new X25519PublicKeyParameters(v("pkRm"));
        var first = HpkeFrames.seal(sender, target, v("info"), v("aad"), v("pt"));
        var second = HpkeFrames.seal(sender, target, v("info"), v("aad"), v("pt"));
        assertFalse(Arrays.equals(first[1], second[1]));
        assertArrayEquals(v("pt"), HpkeFrames.open(recipient, source, v("info"), v("aad"), first[1], first[0]));
    }
    @ParameterizedTest @ValueSource(strings = {"info", "aad", "enc", "ct", "pkSm", "skRm"})
    void rejectsTamperedRfcInputs(String field) {
        var inputs = new HashMap<String, byte[]>();
        for (String name : List.of("info", "aad", "enc", "ct", "pkSm", "skRm")) inputs.put(name, v(name));
        inputs.get(field)[inputs.get(field).length - 1] ^= 1;
        assertThrows(AuthenticationException.class, () -> HpkeFrames.open(HpkeKey.raw(inputs.get("skRm")), new X25519PublicKeyParameters(inputs.get("pkSm")), inputs.get("info"), inputs.get("aad"), inputs.get("enc"), inputs.get("ct")));
    }
    @Test void configuredKeysAreCopiedAndUnknownOrRevokedKeysFail() throws Exception {
        var source = HpkeKey.generate(); byte[] spki = source.publicSpki();
        var entry = new PeerDirectory.Entry("source", "key", Set.of("demo"), spki); spki[0] ^= 1;
        var directory = new PeerDirectory(List.of(entry));
        assertNotNull(directory.require("source", "demo", "key", source.publicHash()));
        assertThrows(AuthenticationException.class, () -> directory.require("source", "wrong", "key", source.publicHash()));
        assertThrows(AuthenticationException.class, () -> directory.require("source", "demo", "missing", source.publicHash()));
        assertThrows(AuthenticationException.class, () -> directory.require("source", "demo", "key", HpkeKey.generate().publicHash()));
        assertThrows(IllegalArgumentException.class, () -> directory.revoke("source", "unknown"));
        directory.revoke("source", "key");
        assertThrows(AuthenticationException.class, () -> directory.require("source", "demo", "key", source.publicHash()));
        assertThrows(AuthenticationException.class, () -> directory.authorizeRoute("source", "demo"));
        byte[] pkcs8 = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(source.pair().getPrivate()).getEncoded();
        assertEquals(source.publicHash(), HpkeKey.fromPkcs8(pkcs8).publicHash());
        assertThrows(IllegalArgumentException.class, () -> new PeerDirectory(List.of(entry, entry)));
    }
}
