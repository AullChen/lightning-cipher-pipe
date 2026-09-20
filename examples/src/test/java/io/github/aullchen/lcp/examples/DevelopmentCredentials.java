package io.github.aullchen.lcp.examples;

import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Short-lived localhost credentials for the documented demo; never packaged in the runtime JAR. */
public final class DevelopmentCredentials {
    private DevelopmentCredentials() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: DevelopmentCredentials new-directory");
        char[] keys = password("LCP_KEYSTORE_PASSWORD"), trust = password("LCP_TRUSTSTORE_PASSWORD");
        try { create(Path.of(args[0]), keys, trust); }
        finally { Arrays.fill(keys, '\0'); Arrays.fill(trust, '\0'); }
        System.out.println("Created localhost credentials for source-a and target-a; TLS expires in one hour");
    }
    private static char[] password(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Set environment variable " + name);
        return value.toCharArray();
    }
    static void create(Path directory, char[] keyPassword, char[] trustPassword) throws Exception {
        // Refuse reuse, even after a partial generation, so existing keys are never replaced.
        Files.createDirectory(directory);
        var ca = Certificates.ca();
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
        trust.setCertificateEntry("ca", ca.certificate());
        try (var out = Files.newOutputStream(directory.resolve("trust.p12"), StandardOpenOption.CREATE_NEW)) {
            trust.store(out, trustPassword);
        }
        for (String role : List.of("source", "target")) {
            var identity = Certificates.leaf(ca, role + "-a", role.equals("source"));
            var keys = KeyStore.getInstance("PKCS12"); keys.load(null, null);
            keys.setKeyEntry("identity", identity.key().getPrivate(), keyPassword,
                    new java.security.cert.Certificate[]{identity.certificate(), ca.certificate()});
            try (var out = Files.newOutputStream(directory.resolve(role + ".p12"), StandardOpenOption.CREATE_NEW)) {
                keys.store(out, keyPassword);
            }
            var hpke = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            Files.write(directory.resolve(role + ".pk8"), hpke.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.write(directory.resolve(role + ".spki"), hpke.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
        }
    }
}
