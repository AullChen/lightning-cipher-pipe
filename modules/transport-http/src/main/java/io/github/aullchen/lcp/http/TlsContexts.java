package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.security.TlsIdentity;
import javax.net.ssl.*;
import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import java.util.Objects;

/** TLS 1.3 with explicit trust anchors and strict LCP SAN/EKU checks on top of PKIX. */
public final class TlsContexts {
    private TlsContexts() {}
    public static SSLContext create(KeyStore keys, char[] password, KeyStore trust, String expectedPeerNode) throws GeneralSecurityException {
        Objects.requireNonNull(trust, "Explicit trust store required");
        if (expectedPeerNode == null || !expectedPeerNode.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid peer node");
        KeyManager[] managers = new KeyManager[0];
        if (keys != null) {
            var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keys, password); managers = factory.getKeyManagers();
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        X509ExtendedTrustManager delegate = null;
        for (var manager : factory.getTrustManagers()) if (manager instanceof X509ExtendedTrustManager x) delegate = x;
        if (delegate == null) throw new GeneralSecurityException("Extended PKIX trust manager required");
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(managers, new TrustManager[]{new IdentityTrust(delegate, expectedPeerNode)}, new SecureRandom()); return context;
    }
    private static final class IdentityTrust extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final String expectedPeerNode;
        IdentityTrust(X509ExtendedTrustManager delegate, String expectedPeerNode) { this.delegate = delegate; this.expectedPeerNode = expectedPeerNode; }
        private void identity(X509Certificate[] chain, TlsIdentity.Role role) throws CertificateException {
            try {
                if (chain.length == 0) throw new CertificateException("Missing identity");
                if (!TlsIdentity.certificate(chain[0], role).nodeId().equals(expectedPeerNode)) throw new CertificateException("Unexpected peer node");
            } catch (SecurityException e) { throw new CertificateException("Invalid LCP certificate identity"); }
        }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkClientTrusted(c, a); identity(c, TlsIdentity.Role.CLIENT); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkServerTrusted(c, a); identity(c, TlsIdentity.Role.SERVER); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { delegate.checkClientTrusted(c, a, s); identity(c, TlsIdentity.Role.CLIENT); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { delegate.checkServerTrusted(c, a, s); identity(c, TlsIdentity.Role.SERVER); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkClientTrusted(c, a, e); identity(c, TlsIdentity.Role.CLIENT); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkServerTrusted(c, a, e); identity(c, TlsIdentity.Role.SERVER); }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }
}
