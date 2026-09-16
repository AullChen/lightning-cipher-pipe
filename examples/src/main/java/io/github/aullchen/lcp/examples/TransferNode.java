package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import io.github.aullchen.lcp.compression.ZstdCompression;
import io.github.aullchen.lcp.http.*;
import io.github.aullchen.lcp.security.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Minimal separate-process FIXED transfer launcher. Secrets are supplied outside configuration. */
public final class TransferNode {
    private TransferNode() {}
    static Properties config(Path path, String role) throws IOException {
        if (!Set.of("source", "target").contains(role)) throw new IllegalArgumentException("Expected source or target");
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
        Set<String> allowed = new HashSet<>(Set.of("nodeId", "peerNodeId", "tlsKeyStore", "tlsTrustStore", "hpkePrivateKey", "hpkeKeyId",
                "peerHpkeKeyId", "peerHpkePublicKey", "routeId", "maxFrameBytes", "maxPlainBytes", "maxChunks", "maxTransferBytes", "bufferBudget", "metadataBudget", "inFlightChunks"));
        allowed.addAll(role.equals("source") ? Set.of("peerUrl", "inputFile", "generatorBytes", "generatorSeed", "chunkBytes", "compression", "zstdLevel", "scheduling")
                : Set.of("listenHost", "listenPort", "outputRoot"));
        for (String name : p.stringPropertyNames()) if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown configuration key: " + name);
        return p;
    }
    private static String required(Properties p, String name) {
        String value = p.getProperty(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration key: " + name); return value;
    }
    private static long number(Properties p, String name, long fallback) {
        String value = p.getProperty(name, Long.toString(fallback));
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid numeric configuration: " + name);
        return Long.parseLong(value);
    }
    private static byte[] small(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) { byte[] bytes = in.readNBytes(65537); if (bytes.length > 65536) throw new IOException("Key file too large"); return bytes; }
    }
    private static char[] password(String name) {
        String value = System.getenv(name); if (value == null || value.isEmpty()) throw new IllegalArgumentException("Set environment variable " + name); return value.toCharArray();
    }
    static Policy policy(Properties p) {
        if (!p.getProperty("scheduling", "FIXED").equals("FIXED"))
            throw new IllegalArgumentException("This launcher currently requires FIXED");
        long chunk = number(p,"chunkBytes",4194304); int level = Math.toIntExact(number(p,"zstdLevel",3));
        if (chunk < 262144 || chunk > 8388608) throw new IllegalArgumentException("Chunk size outside supported range");
        String compression = p.getProperty("compression","ZSTD");
        if (!Set.of("NONE","ZSTD").contains(compression) || level < 1 || level > 5) throw new IllegalArgumentException("Invalid compression configuration");
        if (compression.equals("NONE")) level = 1;
        int window = Math.toIntExact(number(p,"inFlightChunks",1));
        if (window < 1 || window > 16) throw new IllegalArgumentException("Window outside supported range");
        return new Policy(0,1,chunk,chunk,chunk,window,window,window,level,level,level);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: TransferNode source|target configuration.properties");
        String role = args[0]; Properties p = config(Path.of(args[1]), role);
        String node = required(p,"nodeId"), peer = required(p,"peerNodeId"), route = required(p,"routeId");
        HpkeKey key = HpkeKey.fromPkcs8(small(Path.of(required(p,"hpkePrivateKey"))));
        var directory = new PeerDirectory(List.of(new PeerDirectory.Entry(node,required(p,"hpkeKeyId"),Set.of(route),key.publicSpki()),
                new PeerDirectory.Entry(peer,required(p,"peerHpkeKeyId"),Set.of(route),small(Path.of(required(p,"peerHpkePublicKey"))))));
        char[] keyPassword = password("LCP_KEYSTORE_PASSWORD"), trustPassword = password("LCP_TRUSTSTORE_PASSWORD");
        javax.net.ssl.SSLContext tls;
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12"), trust = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(Path.of(required(p,"tlsKeyStore")))) { keys.load(in,keyPassword); }
            try (var in = Files.newInputStream(Path.of(required(p,"tlsTrustStore")))) { trust.load(in,trustPassword); }
            tls = TlsContexts.create(keys,keyPassword,trust,peer);
        } finally { Arrays.fill(keyPassword,'\0'); Arrays.fill(trustPassword,'\0'); }
        var limits = new Limits(number(p,"maxFrameBytes",9437184),number(p,"maxPlainBytes",8388608),number(p,"maxChunks",1000000),number(p,"maxTransferBytes",1099511627776L),Math.toIntExact(number(p,"inFlightChunks",1)));
        var budget = new ByteBudget(number(p,"bufferBudget",134217728)); var codec = new ZstdCompression();
        long metadata = number(p,"metadataBudget",134217728);
        if (limits.maxChunks() > metadata / 52) throw new IllegalArgumentException("Chunk index exceeds metadata budget");
        if (role.equals("target")) {
            try (var sink = new FileSink(Path.of(required(p,"outputRoot")),metadata)) {
                var handler = new TransferHttpHandler(sink,key,directory,route,node,limits,budget,Clock.systemUTC(),Duration.ofMinutes(10),codec);
                try (var server = new AuthenticatedHttpServer(new InetSocketAddress(p.getProperty("listenHost","127.0.0.1"),Math.toIntExact(number(p,"listenPort",9443))),tls,node,route,directory,Math.toIntExact(limits.maxInFlightChunks())+1,32,handler)) {
                    CountDownLatch stopped = new CountDownLatch(1);
                    Thread shutdown = new Thread(stopped::countDown,"lcp-stop"); Runtime.getRuntime().addShutdownHook(shutdown);
                    System.out.println("Listening port=" + server.port() + " configuredWindow=" + limits.maxInFlightChunks());
                    try { stopped.await(); } finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { } }
                }
            }
        } else {
            Policy policy = policy(p); int code = p.getProperty("compression","ZSTD").equals("ZSTD") ? 1 : 0;
            CompressionPlan.validate(policy,limits,CompressionPlan.select(code,codec),budget.capacity());
            if (p.containsKey("inputFile") == p.containsKey("generatorBytes")) throw new IllegalArgumentException("Select exactly one inputFile or generatorBytes");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            UUID id = UUID.randomUUID(); var request = new OpenRequest(id,route,node,peer,new Bytes32(challenge),required(p,"hpkeKeyId"),key.publicHash(),required(p,"peerHpkeKeyId"),
                    MetadataCodec.hash(small(Path.of(required(p,"peerHpkePublicKey")))),List.of(code),policy,limits,now,now.plusSeconds(86400));
            URI endpoint = URI.create(required(p,"peerUrl") + TransferHttpHandler.BASE);
            try (var http = new AuthenticatedHttpClient(tls,Duration.ofSeconds(10),Duration.ofMinutes(11),Math.toIntExact(limits.maxInFlightChunks())+1,32);
                 var sender = new FixedTransferClient(http,key,directory,budget,Clock.systemUTC(),codec,metadata)) {
                TransferSource source = p.containsKey("inputFile") ? new FileSource(Path.of(required(p,"inputFile")))
                        : new GeneratorSource(number(p,"generatorBytes",0),number(p,"generatorSeed",42));
                System.out.println("transferId=" + id + " configuredWindow=" + limits.maxInFlightChunks());
                var result = sender.start(endpoint,request,source).toCompletableFuture().get();
                System.out.println("COMPLETED handleId=" + result.handleId() + " bytes=" + result.totalPlainBytes());
            }
        }
    }
}
