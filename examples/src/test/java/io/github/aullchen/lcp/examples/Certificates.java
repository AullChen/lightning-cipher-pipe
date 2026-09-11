package io.github.aullchen.lcp.examples;
import io.github.aullchen.lcp.http.TlsContexts;

import io.github.aullchen.lcp.security.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;

/** Ephemeral certificates; no private test files or production credentials. */
final class Certificates {
    record Identity(KeyPair key, X509Certificate certificate) {}
    static KeyPair pair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }
    static Identity ca() throws Exception {
        var pair = pair(); var name = new X500Name("CN=LCP test CA");
        var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(128, new SecureRandom()), Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(86400)), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        var cert = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        return new Identity(pair, cert);
    }
    static Identity leaf(Identity ca, String node, boolean client) throws Exception { return leaf(ca, node, client, false, false, "localhost"); }
    static Identity leaf(Identity ca, String node, boolean client, boolean duplicate, boolean expired, String dns) throws Exception {
        var pair = pair(); var issuer = new X500Name(ca.certificate().getSubjectX500Principal().getName());
        var builder = new JcaX509v3CertificateBuilder(issuer, new BigInteger(128, new SecureRandom()), Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(expired ? -60 : 3600)), new X500Name("CN=LCP test node"), pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(client ? KeyPurposeId.id_kp_clientAuth : KeyPurposeId.id_kp_serverAuth));
        var sans = new ArrayList<GeneralName>(); sans.add(new GeneralName(GeneralName.dNSName, dns));
        sans.add(new GeneralName(GeneralName.uniformResourceIdentifier, "urn:unrelated:test"));
        if (node != null) sans.add(new GeneralName(GeneralName.uniformResourceIdentifier, "urn:lcp:node:" + node));
        if (duplicate) sans.add(new GeneralName(GeneralName.uniformResourceIdentifier, "urn:lcp:node:second"));
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray(GeneralName[]::new)));
        var cert = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.key().getPrivate())));
        return new Identity(pair, cert);
    }
    static SSLContext context(Identity identity, Identity issuer, Identity trustedCa, String expectedPeer) throws Exception {
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null); trust.setCertificateEntry("ca", trustedCa.certificate());
        KeyStore keys = null; char[] password = new char[]{'t','e','s','t'};
        if (identity != null) {
            keys = KeyStore.getInstance("PKCS12"); keys.load(null, null);
            keys.setKeyEntry("identity", identity.key().getPrivate(), password, new java.security.cert.Certificate[]{identity.certificate(), issuer.certificate()});
        }
        return TlsContexts.create(keys, password, trust, expectedPeer);
    }
}
