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
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;

/** Isolates window adaptation under a common receiver trace, without changing production policy. */
public final class WindowAdaptationRun {
    public static void main(String[] args) throws Exception {
        if (args.length!=3) throw new IllegalArgumentException("input newOutput F1|F2|F4|A1|A4");
        Path input=Path.of(args[0]), output=Path.of(args[1]); String strategy=args[2];
        if (!Set.of("F1","F2","F4","A1","A4").contains(strategy)) throw new IllegalArgumentException("Unknown strategy");
        Files.createDirectory(output);
        boolean adaptive=strategy.startsWith("A"); int initial=Integer.parseInt(strategy.substring(1));
        var policy=new Policy(adaptive?1:0,adaptive?2:1,262144,262144,262144,
                adaptive?1:initial,adaptive?4:initial,initial,3,3,3);
        long bytes=Files.size(input); String expected=BenchmarkRun.sha256(input);
        var limits=new Limits(300000,262144,4096,Math.max(1,bytes),4);
        var sourceBudget=new ByteBudget(BenchmarkRun.BUDGET); var targetBudget=new ByteBudget(BenchmarkRun.BUDGET);
        var codec=new ZstdCompression(); var epoch=new AtomicLong();
        java.util.function.LongSupplier elapsed=()->epoch.get()==0?0:System.nanoTime()-epoch.get();
        var gate=new ChangingCapacity(elapsed);
        var ca=BenchmarkCertificates.ca();
        var sourceTls=BenchmarkCertificates.context(BenchmarkCertificates.leaf(ca,"source",true),ca,ca,"target");
        var targetTls=BenchmarkCertificates.context(BenchmarkCertificates.leaf(ca,"target",false),ca,ca,"source");
        var sourceKey=HpkeKey.generate(); var targetKey=HpkeKey.generate();
        var directory=new PeerDirectory(List.of(new PeerDirectory.Entry("source","source-key",Set.of("bench"),sourceKey.publicSpki()),
                new PeerDirectory.Entry("target","target-key",Set.of("bench"),targetKey.publicSpki())));
        var io=new FileSink.Io() {
            public void after(FileSink.Boundary boundary) throws IOException {
                if (boundary==FileSink.Boundary.PAYLOAD_WRITTEN) try { Thread.sleep(80); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
        var result=new LinkedHashMap<String,Object>();
        result.put("strategy",strategy); result.put("inputBytes",bytes); result.put("inputSha256",expected);
        result.put("initialWindow",initial); result.put("policyVersion",policy.policyVersion());
        result.put("chunkBytes",262144); result.put("zstdLevel",3); result.put("sampleMillis",250);
        result.put("bufferBudgetPerPeer",BenchmarkRun.BUDGET); result.put("metadataBudgetPerPeer",BenchmarkRun.METADATA);
        result.put("processors",Runtime.getRuntime().availableProcessors()); result.put("javaVersion",System.getProperty("java.version"));
        try (var sink=new FileSink(output.resolve("sink"),BenchmarkRun.METADATA,io);
             var handler=new TransferHttpHandler(sink,targetKey,directory,"bench","target",limits,targetBudget,Clock.systemUTC(),Duration.ofMinutes(2),codec);
             var server=new AuthenticatedHttpServer(new InetSocketAddress("127.0.0.1",0),targetTls,"target","bench",directory,9,32,(x,source,target)->{
                 boolean chunk=x.getRequestURI().getPath().contains("/chunks/");
                 if (chunk && !gate.enter()) {
                     byte[] error=TransferJson.error(TransferStorage.ErrorCode.BUSY);
                     x.getResponseHeaders().set("Retry-After","1"); x.getResponseHeaders().set("Content-Type","application/json");
                     x.sendResponseHeaders(429,error.length); x.getResponseBody().write(error); return;
                 }
                 try { handler.handle(x,source,target); } finally { if (chunk) gate.leave(); }
             });
             var http=new AuthenticatedHttpClient(sourceTls,Duration.ofSeconds(10),Duration.ofSeconds(30),9,32);
             var sender=new FixedTransferClient(http,sourceKey,directory,sourceBudget,Clock.systemUTC(),codec,BenchmarkRun.METADATA);
             var trace=new PrintWriter(Files.newBufferedWriter(output.resolve("trace.csv")))) {
            trace.println("seconds,capacity,active,confirmedBytes,window,ackP95Millis,busy,retries,gateBusy,decisions,started,evaluated,retained,rolledBack,interrupted");
            Runnable sample=()->{
                var m=sender.metrics(); var t=m.feedback().window();
                trace.printf(Locale.ROOT,"%.6f,%d,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        elapsed.getAsLong()/1e9,gate.capacity(),gate.active.get(),m.confirmedBytes(),m.attempts()==0?"":Integer.toString(m.effectiveWindow()),
                        m.ackP95Nanos()==null?"":Double.toString(m.ackP95Nanos()/1e6),m.busy(),m.retries(),gate.rejected.get(),m.decisions(),
                        t.started(),t.evaluated(),t.retained(),t.rolledBack(),t.interrupted());
                trace.flush();
            };
            var sampler=Executors.newSingleThreadScheduledExecutor();
            var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); UUID id=UUID.randomUUID();
            var request=new OpenRequest(id,"bench","source","target",MetadataCodec.hash(new byte[]{1}),"source-key",sourceKey.publicHash(),
                    "target-key",targetKey.publicHash(),List.of(1),policy,limits,now,now.plusSeconds(600));
            long cpu=os.getProcessCpuTime(); epoch.set(System.nanoTime());
            var sampling=sampler.scheduleAtFixedRate(sample,0,250,TimeUnit.MILLISECONDS);
            try {
                var verified=sender.transfer(URI.create("https://localhost:"+server.port()+TransferHttpHandler.BASE),request,new FileSource(input));
                result.put("completionNanos",elapsed.getAsLong()); result.put("cpuNanos",os.getProcessCpuTime()-cpu);
                result.put("chunks",verified.totalChunks()); result.put("verifiedBytes",verified.totalPlainBytes());
                result.put("status","COMPLETED");
            } catch (Exception e) {
                result.put("completionNanos",elapsed.getAsLong()); result.put("cpuNanos",os.getProcessCpuTime()-cpu);
                result.put("status","FAILED"); result.put("error",e instanceof TransferException t?t.code().name():e.getClass().getSimpleName());
            } finally {
                sampling.cancel(false); sampler.shutdown();
                if (!sampler.awaitTermination(5,TimeUnit.SECONDS)) throw new IOException("Trace sampler did not exit");
                // Scheduled executors otherwise hide sampling exceptions in the Future.
                if (!sampling.isCancelled()) sampling.get();
                sample.run(); if (trace.checkError()) throw new IOException("Trace write failed");
            }
            if (result.get("status").equals("COMPLETED")) {
                String actual=BenchmarkRun.sha256(output.resolve("sink").resolve(id.toString()).resolve("payload.bin"));
                result.put("outputSha256",actual);
                if (!expected.equals(actual) || !Long.valueOf(bytes).equals(result.get("verifiedBytes"))) throw new IOException("Output mismatch");
            }
            var m=sender.metrics(); var t=m.feedback().window();
            result.put("sourceLeasedBytesAtEnd",sourceBudget.used()); result.put("targetLeasedBytesAtEnd",targetBudget.used());
            result.put("busy",m.busy()); result.put("gateBusy",gate.rejected.get()); result.put("retries",m.retries());
            result.put("retriedFrameBytes",m.retriedFrameBytes()); result.put("decisionNanos",m.decisionNanos());
            result.put("decisions",m.decisions()); result.put("started",t.started()); result.put("evaluated",t.evaluated());
            result.put("retained",t.retained()); result.put("rolledBack",t.rolledBack()); result.put("interrupted",t.interrupted());
            result.put("finalWindow",m.effectiveWindow()); result.put("capacityActiveAtEnd",gate.active.get());
        }
        try (var writer=new JsonFactory().createGenerator(output.resolve("result.json").toFile(),com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            writer.writeStartObject();
            for (var entry:result.entrySet()) {
                writer.writeFieldName(entry.getKey()); Object value=entry.getValue();
                if (value instanceof Number n) writer.writeNumber(n.longValue());
                else writer.writeString(value.toString());
            }
            writer.writeEndObject();
        }
        System.out.println(result.get("status"));
    }
}
