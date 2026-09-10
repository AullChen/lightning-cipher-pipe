package io.github.aullchen.lcp.security;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.util.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Local X25519 private key. Private material is never exposed through protocol objects. */
public final class HpkeKey {
    private final X25519PrivateKeyParameters key;
    private HpkeKey(X25519PrivateKeyParameters key) { this.key = key; }
    public static HpkeKey generate() { return new HpkeKey(new X25519PrivateKeyParameters(new SecureRandom())); }
    public static HpkeKey fromPkcs8(byte[] encoded) throws IOException {
        var key = PrivateKeyFactory.createKey(encoded);
        if (!(key instanceof X25519PrivateKeyParameters x)) throw new IOException("Expected X25519 private key");
        return new HpkeKey(x);
    }
    // Only package tests use RFC raw key material. Applications use PKCS8.
    static HpkeKey raw(byte[] raw) {
        if (raw.length != 32) throw new IllegalArgumentException("Expected X25519 key");
        return new HpkeKey(new X25519PrivateKeyParameters(raw));
    }
    AsymmetricCipherKeyPair pair() { return new AsymmetricCipherKeyPair(key.generatePublicKey(), key); }
    public byte[] publicSpki() {
        try { return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.generatePublicKey()).getEncoded(); }
        catch (IOException e) { throw new IllegalStateException("Cannot encode public key", e); }
    }
    public Bytes32 publicHash() { return MetadataCodec.hash(publicSpki()); }
    static X25519PublicKeyParameters publicKey(byte[] spki) {
        try {
            var key = PublicKeyFactory.createKey(spki);
            if (!(key instanceof X25519PublicKeyParameters x) || !Arrays.equals(spki, SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key).getEncoded()))
                throw new IllegalArgumentException("Expected canonical X25519 SPKI");
            return x;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid public key", e); }
    }
    @Override public String toString() { return "HpkeKey[redacted]"; }
}
