package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.compression.ZstdCompression;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.*;

/** Isolated framing allocation diagnostic, not a throughput or native-memory benchmark. */
public final class FrameAllocationRun {
    private static volatile Object retained;
    private static byte[] bytes(ByteBuffer b) { var out=new byte[b.remaining()]; b.get(out); return out; }
    public static void main(String[] args) throws Exception {
        String role=args[0], path=args[1]; int code=Integer.parseInt(args[2]), size=Integer.parseInt(args[3]);
        if (!Set.of("source","target").contains(role) || !Set.of("legacy","direct").contains(path)) throw new IllegalArgumentException();
        ChunkCompression codec=code==0?ChunkCompression.NONE:new ZstdCompression();
        byte[] plain=new byte[size]; new Random(42).nextBytes(plain);
        byte[] compressed=codec.compress(plain,1), cipher=new byte[compressed.length+16], enc=new byte[32];
        var limits=new Limits(9437184,8388608,128,16777216,4);
        var aad=MetadataCodec.encode(new ChunkAad(UUID.randomUUID(),new Bytes32(new byte[32]),0,0,size,compressed.length,code));
        byte[] wire=FrameCodec.encode(0,aad,enc,cipher,limits);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation accounting unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i=0;i<20;i++) operation(role,path,wire,aad,enc,cipher,limits);
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for (int i=0;i<40;i++) operation(role,path,wire,aad,enc,cipher,limits);
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        System.out.println(role+","+path+","+code+","+size+","+wire.length+","+allocated/40+","+
                Math.min(4,128L*1024*1024/CompressionPlan.peak(limits,codec)));
    }
    private static void operation(String role,String path,byte[] wire,byte[] aad,byte[] enc,byte[] cipher,Limits limits) throws Exception {
        if (role.equals("source")) { retained=FrameCodec.encode(0,aad,enc,cipher,limits); return; }
        var frame=FrameCodec.read(new ByteArrayInputStream(wire),wire.length,limits);
        if (path.equals("legacy")) {
            var encoded=FrameCodec.encode(frame.messageType(),bytes(frame.aad()),bytes(frame.enc()),bytes(frame.ciphertext()),limits);
            frame=FrameCodec.decode(ByteBuffer.wrap(encoded),limits);
        }
        FrameCodec.validate(frame,limits); retained=frame;
    }
}
