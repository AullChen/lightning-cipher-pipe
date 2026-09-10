package io.github.aullchen.lcp.security;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import javax.net.ssl.SSLSession;
import java.security.cert.*;

/** Identity derived from a verified TLS 1.3 session, never from HTTP headers. */
public final class TlsIdentity {
    public enum Role { CLIENT, SERVER }
    private final String nodeId;
    private final Bytes32 spkiHash;
    private TlsIdentity(String nodeId, Bytes32 spkiHash) { this.nodeId = nodeId; this.spkiHash = spkiHash; }
    public String nodeId() { return nodeId; }
    public Bytes32 spkiHash() { return spkiHash; }
    public static TlsIdentity peer(SSLSession session, Role role) {
        try {
            if (!"TLSv1.3".equals(session.getProtocol())) throw new AuthenticationException();
            return certificate((X509Certificate) session.getPeerCertificates()[0], role);
        } catch (javax.net.ssl.SSLPeerUnverifiedException | ClassCastException e) { throw new AuthenticationException(); }
    }
    public static TlsIdentity local(SSLSession session, Role role) {
        if (!"TLSv1.3".equals(session.getProtocol()) || session.getLocalCertificates() == null) throw new AuthenticationException();
        return certificate((X509Certificate) session.getLocalCertificates()[0], role);
    }
    /** Certificate chains must first be validated by JSSE's PKIX trust manager. */
    public static TlsIdentity certificate(X509Certificate certificate, Role role) {
        try {
            certificate.checkValidity();
            String purpose = role == Role.CLIENT ? "1.3.6.1.5.5.7.3.2" : "1.3.6.1.5.5.7.3.1";
            var eku = certificate.getExtendedKeyUsage();
            if (eku == null || !eku.contains(purpose)) throw new AuthenticationException();
            var names = certificate.getSubjectAlternativeNames(); String node = null;
            if (names != null) for (var san : names) if (Integer.valueOf(6).equals(san.get(0)) && san.get(1) instanceof String uri && uri.startsWith("urn:lcp:node:")) {
                if (node != null) throw new AuthenticationException();
                node = uri.substring("urn:lcp:node:".length()); PeerDirectory.identifier(node);
            }
            if (node == null) throw new AuthenticationException();
            return new TlsIdentity(node, MetadataCodec.hash(certificate.getPublicKey().getEncoded()));
        } catch (CertificateException | IllegalArgumentException e) { throw new AuthenticationException(); }
    }
}
