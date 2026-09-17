package io.github.aullchen.lcp.examples;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TransferNodeTest extends StorageTestSupport {
    @Test void configurationRejectsUnknownKeysAndUnsupportedPolicy() throws Exception {
        Path file = root.resolve("node.properties"); Files.writeString(file,"unknown=value\n");
        assertThrows(IllegalArgumentException.class, () -> TransferNode.config(file,"source"));
        Properties p = new Properties(); p.setProperty("inFlightChunks","17");
        assertThrows(IllegalArgumentException.class, () -> TransferNode.policy(p));
        p.setProperty("inFlightChunks","1"); p.setProperty("compression","NONE");
        assertEquals(1, TransferNode.policy(p).initialZstdLevel());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void separateProcessesRunConfiguredZstdTransfer(boolean crashTarget) throws Exception {
        var ca = Certificates.ca(); var source = Certificates.leaf(ca,"source-a",true); var target = Certificates.leaf(ca,"target-a",false);
        char[] password = "temporary-test-password".toCharArray();
        KeyStore trust = KeyStore.getInstance("PKCS12"); trust.load(null,null); trust.setCertificateEntry("ca",ca.certificate());
        Path trustFile = root.resolve("trust.p12"); try (var out = Files.newOutputStream(trustFile)) { trust.store(out,password); }
        for (String role : List.of("source","target")) {
            var identity = role.equals("source") ? source : target;
            KeyStore keys = KeyStore.getInstance("PKCS12"); keys.load(null,null);
            keys.setKeyEntry("identity",identity.key().getPrivate(),password,new java.security.cert.Certificate[]{identity.certificate(),ca.certificate()});
            try (var out = Files.newOutputStream(root.resolve(role+".p12"))) { keys.store(out,password); }
            var hpke = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            Files.write(root.resolve(role+".pk8"),hpke.getPrivate().getEncoded()); Files.write(root.resolve(role+".spki"),hpke.getPublic().getEncoded());
        }
        Properties targetConfig = properties("target",trustFile);
        targetConfig.setProperty("listenPort","0"); targetConfig.setProperty("outputRoot",root.resolve("output").toAbsolutePath().toString());
        Path targetFile = save("target",targetConfig), targetLog = root.resolve("target.log");
        Process server = launch("target",targetFile,targetLog,crashTarget ? CrashTarget.class.getName() : TransferNode.class.getName());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); String log = "";
            while (!log.contains("configuredWindow=1") && server.isAlive() && System.nanoTime() < deadline) {
                if (Files.exists(targetLog)) log = Files.readString(targetLog); Thread.sleep(25);
            }
            var matcher = java.util.regex.Pattern.compile("Listening port=(\\d+)").matcher(log);
            assertTrue(matcher.find(),log);
            Properties sourceConfig = properties("source",trustFile);
            sourceConfig.setProperty("peerUrl","https://localhost:"+matcher.group(1));
            sourceConfig.setProperty("generatorBytes","524289"); sourceConfig.setProperty("chunkBytes","262144");
            Path sourceLog = root.resolve("source.log");
            Process sender = launch("source",save("source",sourceConfig),sourceLog);
            try {
                if (crashTarget) {
                    assertTrue(server.waitFor(10,TimeUnit.SECONDS)); assertEquals(73,server.exitValue());
                    targetConfig.setProperty("listenPort",matcher.group(1));
                    server=launch("target",save("target",targetConfig),root.resolve("restarted-target.log"));
                }
                assertTrue(sender.waitFor(25,TimeUnit.SECONDS)); assertEquals(0,sender.exitValue(),Files.readString(sourceLog));
                assertTrue(Files.readString(sourceLog).contains("COMPLETED"));
            } finally { if (sender.isAlive()) { sender.destroyForcibly(); sender.waitFor(10,TimeUnit.SECONDS); } }
            // A fresh source JVM must query the saved task, not open another input or duplicate output.
            sourceConfig.setProperty("inputFile",root.resolve("does-not-exist.bin").toString()); sourceConfig.remove("generatorBytes");
            Path replayLog=root.resolve("replay.log"); Process replay=launch("source",save("source",sourceConfig),replayLog);
            try { assertTrue(replay.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,replay.exitValue(),Files.readString(replayLog)); assertTrue(Files.readString(replayLog).contains("COMPLETED")); }
            finally { if (replay.isAlive()) { replay.destroyForcibly(); replay.waitFor(5,TimeUnit.SECONDS); } }
            try (var paths = Files.list(root.resolve("output"))) {
                Path output = paths.filter(p -> Files.exists(p.resolve("payload.bin"))).findFirst().orElseThrow();
                byte[] bytes = Files.readAllBytes(output.resolve("payload.bin")); assertEquals(524289,bytes.length);
                var random = new SplittableRandom(42); for (byte b : bytes) assertEquals((byte)random.nextInt(256),b);
            }
        } finally { server.destroy(); if (!server.waitFor(10,TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(10,TimeUnit.SECONDS); } }
    }
    private Properties properties(String role,Path trust) {
        String peer = role.equals("source") ? "target" : "source"; Properties p = new Properties();
        if (role.equals("source")) p.setProperty("controlRecord",root.resolve("source-control.cbor").toAbsolutePath().toString());
        p.setProperty("nodeId",role+"-a"); p.setProperty("peerNodeId",peer+"-a"); p.setProperty("routeId","demo");
        p.setProperty("hpkeKeyId",role+"-hpke-1"); p.setProperty("peerHpkeKeyId",peer+"-hpke-1");
        p.setProperty("tlsTrustStore",trust.toAbsolutePath().toString()); p.setProperty("tlsKeyStore",root.resolve(role+".p12").toAbsolutePath().toString());
        p.setProperty("hpkePrivateKey",root.resolve(role+".pk8").toAbsolutePath().toString()); p.setProperty("peerHpkePublicKey",root.resolve(peer+".spki").toAbsolutePath().toString());
        p.setProperty("maxPlainBytes","262144"); p.setProperty("maxFrameBytes","300000"); p.setProperty("maxChunks","32"); p.setProperty("maxTransferBytes","8388608");
        p.setProperty("metadataBudget","4096"); p.setProperty("bufferBudget","67108864"); return p;
    }
    private Path save(String role,Properties p) throws Exception {
        Path file = root.resolve(role+".properties"); try (var out = Files.newBufferedWriter(file)) { p.store(out,null); } return file;
    }
    public static class CrashTarget {
        public static void main(String[] args) throws Exception {
            TransferNode.run(args,new FileSink.Io(){
                public void after(FileSink.Boundary boundary) { if (boundary==FileSink.Boundary.RECEIPT_FORCED) Runtime.getRuntime().halt(73); }
            });
        }
    }
    private Process launch(String role,Path file,Path log) throws Exception { return launch(role,file,log,TransferNode.class.getName()); }
    private Process launch(String role,Path file,Path log,String main) throws Exception {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),
                "-Dsun.net.httpserver.maxReqTime=30","-Dsun.net.httpserver.maxRspTime=30","-Djdk.httpserver.maxConnections=16",
                "-cp",System.getProperty("java.class.path"),main,role,file.toAbsolutePath().toString());
        builder.environment().put("LCP_KEYSTORE_PASSWORD","temporary-test-password"); builder.environment().put("LCP_TRUSTSTORE_PASSWORD","temporary-test-password");
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
}
