package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.security.*;
import com.sun.net.httpserver.*;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Reference TLS transport boundary; no transfer endpoints or persistence are registered here.
 * JDK server connection/request/response limits must be set as JVM startup properties. */
public final class AuthenticatedHttpServer implements AutoCloseable {
    @FunctionalInterface public interface Handler {
        void handle(HttpsExchange exchange, TlsIdentity source, TlsIdentity target) throws IOException;
    }
    private final HttpsServer server;
    private final BoundedExecutor executor;
    public AuthenticatedHttpServer(InetSocketAddress address, SSLContext context, String localNode, String route,
                                    PeerDirectory directory, int workers, int queued, Handler handler) throws IOException {
        positiveProperty("sun.net.httpserver.maxReqTime", Long.MAX_VALUE / 1000); positiveProperty("sun.net.httpserver.maxRspTime", Long.MAX_VALUE / 1000);
        positiveProperty("jdk.httpserver.maxConnections", Integer.MAX_VALUE);
        executor = new BoundedExecutor(workers, queued, "lcp-https");
        HttpsServer created = null;
        try {
            created = HttpsServer.create(address, queued);
            created.setHttpsConfigurator(new HttpsConfigurator(context) {
                @Override public void configure(HttpsParameters parameters) {
                    var ssl = context.getDefaultSSLParameters(); ssl.setProtocols(new String[]{"TLSv1.3"});
                    ssl.setNeedClientAuth(true); parameters.setSSLParameters(ssl);
                }
            });
            created.setExecutor(executor);
            created.createContext("/", exchange -> {
                try (exchange) {
                    var https = (HttpsExchange) exchange;
                    TlsIdentity source, target;
                    try {
                        source = TlsIdentity.peer(https.getSSLSession(), TlsIdentity.Role.CLIENT);
                        target = TlsIdentity.local(https.getSSLSession(), TlsIdentity.Role.SERVER);
                        if (!target.nodeId().equals(localNode)) throw new AuthenticationException();
                        directory.authorizeRoute(source.nodeId(), route);
                    } catch (SecurityException e) { exchange.sendResponseHeaders(403, -1); return; }
                    handler.handle(https, source, target);
                }
            });
            created.start(); server = created;
        } catch (IOException | RuntimeException e) { if (created != null) created.stop(0); executor.close(); throw e; }
    }
    private static void positiveProperty(String name, long maximum) {
        try { long value = Long.parseLong(System.getProperty(name, "0")); if (value <= 0 || value > maximum) throw new NumberFormatException(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Set positive JVM startup property " + name); }
    }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.close(); }
}
