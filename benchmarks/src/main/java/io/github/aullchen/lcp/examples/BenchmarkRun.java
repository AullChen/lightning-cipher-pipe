package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.compression.ZstdCompression;
import io.github.aullchen.lcp.http.*;
import io.github.aullchen.lcp.security.*;
import com.fasterxml.jackson.core.JsonFactory;
import java.io.*;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** One fresh JVM, real TLS/HPKE and durable FileSink. No external credentials or private inputs. */
public final class BenchmarkRun {
    static final long BUDGET=512L*1024*1024, METADATA=8L*1024*1024;
    static Policy policy(String strategy,int chunk,int window,int level) {
        return switch(strategy) {
            case "B0", "B1" -> new Policy(0,1,chunk,chunk,chunk,window,window,window,level,level,level);
            case "B2" -> new Policy(1,1,chunk,chunk,chunk,1,16,window,level,level,level);
            case "WC" -> new Policy(1,1,262144,8388608,chunk,1,16,window,level,level,level);
            case "FULL" -> new Policy(1,1,262144,8388608,chunk,1,16,window,1,5,level);
            default -> throw new IllegalArgumentException("Unknown strategy");
        };
    }
    static String sha256(Path path) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try (var input=Files.newInputStream(path)) { byte[] buffer=new byte[65536]; int n;
            while ((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        if (args.length!=9) throw new IllegalArgumentException("input outputDirectory strategy scenario train|eval chunk window level maxChunks");
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        Files.createDirectory(output); // refuse overwriting any previous result or output
        String strategy=args[2], scenario=args[3]; boolean training=args[4].equals("train");
        int chunk=Integer.parseInt(args[5]), window=Integer.parseInt(args[6]), level=Integer.parseInt(args[7]);
        long maxChunks=Long.parseLong(args[8]); long bytes=Files.size(input);
        var policy=policy(strategy,chunk,window,level);
        var limits=new Limits(9437184,8388608,maxChunks,Math.max(1,bytes),16);
        var sourceBudget=new ByteBudget(BUDGET); var targetBudget=new ByteBudget(BUDGET);
        var codec=new ZstdCompression(); CompressionPlan.validate(policy,limits,codec,BUDGET);
        String expected=sha256(input);
        var ca=BenchmarkCertificates.ca();
        var sourceTls=BenchmarkCertificates.context(BenchmarkCertificates.leaf(ca,"source",true),ca,ca,"target");
        var targetTls=BenchmarkCertificates.context(BenchmarkCertificates.leaf(ca,"target",false),ca,ca,"source");
        var sourceKey=HpkeKey.generate(); var targetKey=HpkeKey.generate();
        var directory=new PeerDirectory(List.of(new PeerDirectory.Entry("source","source-key",Set.of("bench"),sourceKey.publicSpki()),
                new PeerDirectory.Entry("target","target-key",Set.of("bench"),targetKey.publicSpki())));
        var io=new FileSink.Io() {
            public void after(FileSink.Boundary boundary) throws IOException {
                if (scenario.equals("sink") && boundary==FileSink.Boundary.PAYLOAD_WRITTEN) {
                    try { Thread.sleep(training?80:160); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
            }
        };
        var result=new LinkedHashMap<String,Object>();
        result.put("strategy",strategy); result.put("scenario",scenario); result.put("trace",training?"train-v1":"eval-v1");
        result.put("inputBytes",bytes); result.put("inputSha256",expected); result.put("policyVersion",1);
        result.put("initialChunk",chunk); result.put("initialWindow",window); result.put("initialLevel",level);
        result.put("effectiveInitialWindow",Math.min(window,BUDGET/CompressionPlan.peak(limits,codec)));
        result.put("bufferBudgetPerPeer",BUDGET); result.put("metadataBudgetPerPeer",METADATA); result.put("maxChunks",maxChunks);
        result.put("compressionWorkers",1); result.put("sourceAndTargetSameJvm",true);
        try (var sink=new FileSink(output.resolve("sink"),METADATA,io);
             var handler=new TransferHttpHandler(sink,targetKey,directory,"bench","target",limits,targetBudget,Clock.systemUTC(),Duration.ofMinutes(2),codec);
             var server=new AuthenticatedHttpServer(new InetSocketAddress("127.0.0.1",0),targetTls,"target","bench",directory,17,32,handler);
             var link=new ShapedLink(server.port(),scenario,training);
             var http=new AuthenticatedHttpClient(sourceTls,Duration.ofSeconds(10),Duration.ofSeconds(30),17,32);
             var sender=new FixedTransferClient(http,sourceKey,directory,sourceBudget,Clock.systemUTC(),codec,METADATA)) {
            Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); UUID id=UUID.randomUUID();
            var request=new OpenRequest(id,"bench","source","target",MetadataCodec.hash(new byte[]{1}),"source-key",sourceKey.publicHash(),
                    "target-key",targetKey.publicHash(),List.of(1),policy,limits,now,now.plusSeconds(600));
            var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            long cpu=os.getProcessCpuTime(), start=System.nanoTime();
            try {
                var verified=sender.transfer(URI.create("https://localhost:"+link.port()+TransferHttpHandler.BASE),request,new FileSource(input));
                result.put("completionNanos",System.nanoTime()-start); result.put("cpuNanos",os.getProcessCpuTime()-cpu);
                String actual=sha256(output.resolve("sink").resolve(id.toString()).resolve("payload.bin"));
                if (!expected.equals(actual) || verified.totalPlainBytes()!=bytes) throw new IOException("Output mismatch");
                result.put("status","COMPLETED"); result.put("outputSha256",actual); result.put("chunks",verified.totalChunks());
            } catch (Exception e) {
                result.put("completionNanos",System.nanoTime()-start); result.put("cpuNanos",os.getProcessCpuTime()-cpu);
                result.put("status","FAILED"); result.put("errorType",e.getClass().getSimpleName());
                result.put("errorReason",e instanceof TransferException t ? t.code().name()
                        : "Transfer quota exceeded".equals(e.getMessage()) ? "TRANSFER_QUOTA" : "UNCLASSIFIED");
            }
            var m=sender.metrics();
            result.put("confirmedBytes",m.confirmedBytes()); result.put("attempts",m.attempts()); result.put("retries",m.retries());
            result.put("busy",m.busy()); result.put("budgetFailures",m.budgetFailures()); result.put("samples",m.samples());
            result.put("ackP95Nanos",m.ackP95Nanos()); result.put("queueShare",m.queueShare());
            result.put("compressedBytes",m.compressedBytes()); result.put("compressionNanos",m.compressionNanos()); result.put("sealNanos",m.sealNanos());
            result.put("sentFrameBytes",m.sentFrameBytes()); result.put("retriedFrameBytes",m.retriedFrameBytes());
            result.put("decisions",m.decisions()); result.put("trials",m.trials()); result.put("rollbacks",m.rollbacks()); result.put("decisionNanos",m.decisionNanos());
            result.put("windowFullNanos",m.windowFullNanos()); result.put("budgetBlockedNanos",m.budgetBlockedNanos());
            result.put("tlsUpstreamBytes",link.upstream.get()); result.put("tlsDownstreamBytes",link.downstream.get());
            result.put("disconnectEvents",link.disconnects.get());
            result.put("sourceLeasedBytesAtEnd",sourceBudget.used()); result.put("targetLeasedBytesAtEnd",targetBudget.used());
            long peakHeap=ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType()==java.lang.management.MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            result.put("peakHeapPoolSum",peakHeap);
        }
        result.put("javaVersion",System.getProperty("java.version")); result.put("os",System.getProperty("os.name"));
        result.put("processors",Runtime.getRuntime().availableProcessors());
        try (var writer=new JsonFactory().createGenerator(output.resolve("result.json").toFile(),com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            writer.writeStartObject();
            for (var entry:result.entrySet()) {
                writer.writeFieldName(entry.getKey()); Object value=entry.getValue();
                if (value==null) writer.writeNull(); else if (value instanceof Long n) writer.writeNumber(n);
                else if (value instanceof Integer n) writer.writeNumber(n); else if (value instanceof Double n) writer.writeNumber(n);
                else if (value instanceof Boolean b) writer.writeBoolean(b); else writer.writeString(value.toString());
            }
            writer.writeEndObject();
        }
        System.out.println(result.get("status"));
    }
}
