package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import io.github.aullchen.lcp.core.stream.ReceiptLedger;
import com.fasterxml.jackson.core.JsonFactory;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32C;
import java.lang.management.ManagementFactory;
import static java.nio.file.StandardOpenOption.*;

/** Storage/index stress only: the prefilled journal is a fixture, not network or ACK evidence. */
public final class IndexScaleRun {
    private static final Bytes32[] HASHES = new Bytes32[256];
    static { for (int i=0;i<256;i++) HASHES[i]=MetadataCodec.hash(new byte[]{(byte)i}); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static Receipt receipt(UUID id, int i) { return new Receipt(id,i,i,1,HASHES[i&255]); }
    private static Chunk chunk(int i) { return new Chunk(i,i,1,HASHES[i&255],ByteBuffer.wrap(new byte[]{(byte)i})); }
    private static AcceptedTransfer transfer(int count) {
        UUID id=UUID.randomUUID(); Bytes32 hash=MetadataCodec.hash(new byte[0]);
        Instant now=Instant.parse("2026-09-20T00:00:00Z");
        var policy=new Policy(0,1,1,1,1,1,1,1,1,1,1);
        var limits=new Limits(4096,1,count,count+1L,1);
        var request=new OpenRequest(id,"index-check","source","target",hash,"source-key",hash,
                "target-key",hash,List.of(0),policy,limits,now,now.plusSeconds(3600));
        var accepted=new Accepted(id,hash,id.toString(),0,policy,limits,request.expiresAt());
        return new AcceptedTransfer(request,new OpenResponse(accepted,MetadataCodec.openRequestHash(request),
                MetadataCodec.bindingHash(request,accepted,hash,hash)),hash,hash);
    }
    private static void write(FileChannel channel, ByteBuffer buffer) throws Exception {
        buffer.flip(); while(buffer.hasRemaining()) require(channel.write(buffer)>0,"No file progress"); buffer.clear();
    }
    private static void fixture(Path directory, int count) throws Exception {
        try(var payload=FileChannel.open(directory.resolve("payload.bin"),WRITE,TRUNCATE_EXISTING);
            var journal=FileChannel.open(directory.resolve("receipts.log"),WRITE,TRUNCATE_EXISTING)) {
            ByteBuffer bytes=ByteBuffer.allocate(65536), records=ByteBuffer.allocate(68*512);
            for(int i=0;i<count;i++) {
                bytes.put((byte)i); if(!bytes.hasRemaining()) write(payload,bytes);
                int start=records.position();
                records.putInt(0x4c435052).putInt(1).putLong(i).putLong(i).putLong(1).put(HASHES[i&255].bytes());
                var crc=new CRC32C(); crc.update(records.array(),start,64); records.putInt((int)crc.getValue());
                if(!records.hasRemaining()) write(journal,records);
            }
            write(payload,bytes); write(journal,records); payload.force(true); journal.force(true);
        }
    }
    private static long reconcile(SinkSession session, ReceiptLedger ledger, UUID id, int count) throws Exception {
        long pages=0;
        for(int from=0;from<count;from+=256) {
            int limit=Math.min(256,count-from); var page=session.receipts(from,limit);
            require(page.entries().size()==limit,"Missing receipt");
            for(int j=0;j<limit;j++) require(page.entries().get(j).equals(receipt(id,from+j)),"Changed receipt");
            ledger.reconcile(page,from,limit,0); pages++;
        }
        require(ledger.confirmedBytes()==count,"Duplicate or missing confirmed bytes");
        return pages;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("new-output-directory slots");
        Path output=Path.of(args[0]); int slots=Integer.parseInt(args[1]);
        if(slots<2 || slots>1000000) throw new IllegalArgumentException("Expected 2..1000000 slots");
        Files.createDirectory(output); long budget=52L*slots; var transfer=transfer(slots); UUID id=transfer.request().transferId();
        long started=System.nanoTime();
        try { new ReceiptLedger(id,slots,budget-1); throw new IllegalStateException("Source under-budget accepted"); }
        catch(IllegalArgumentException expected) { }
        Path sinkRoot=output.resolve("sink");
        try(var sink=new FileSink(sinkRoot,budget-1)) {
            try { sink.open(transfer); throw new IllegalStateException("Sink under-budget accepted"); }
            catch(TransferException expected) { require(expected.code()==ErrorCode.LIMIT_EXCEEDED,"Wrong budget rejection"); }
            require(!Files.exists(sinkRoot.resolve(id.toString())),"Rejected Open created transfer");
        }
        try(var sink=new FileSink(sinkRoot,budget)) { sink.open(transfer); }
        Path directory=sinkRoot.resolve(id.toString()); fixture(directory,slots-1);
        var ledger=new ReceiptLedger(id,slots,budget);
        for(int i=0;i<slots-1;i++) { var r=receipt(id,i); ledger.assign(r); ledger.acknowledge(r); }
        long recoveredAt=System.nanoTime(), recoveryNanos, pages;
        try(var sink=new FileSink(sinkRoot,budget)) {
            var session=sink.recover(id); recoveryNanos=System.nanoTime()-recoveredAt;
            require(session.state().committedChunks()==slots-1,"Wrong recovered count");
            pages=reconcile(session,ledger,id,slots-1);
            var last=receipt(id,slots-1); ledger.assign(last);
            require(session.commit(chunk(slots-1)).equals(last),"Last slot commit mismatch"); ledger.acknowledge(last);
            require(session.commit(chunk(slots-1)).equals(last),"Duplicate commit changed receipt");
            require(Files.size(directory.resolve("receipts.log"))==68L*slots,"Duplicate journal record");
            try { ledger.assign(receipt(id,slots)); throw new IllegalStateException("Source overflow accepted"); }
            catch(IllegalArgumentException expected) { }
            try { session.commit(chunk(slots)); throw new IllegalStateException("Sink overflow accepted"); }
            catch(TransferException expected) { require(expected.code()==ErrorCode.LIMIT_EXCEEDED,"Wrong slot rejection"); }
            require(session.state().committedChunks()==slots,"Overflow changed count");
            require(Files.size(directory.resolve("payload.bin"))==slots,"Overflow changed payload");
        }
        long reopenAt=System.nanoTime(), reopenNanos;
        try(var sink=new FileSink(sinkRoot,budget)) {
            var session=sink.recover(id); reopenNanos=System.nanoTime()-reopenAt;
            require(session.state().committedChunks()==slots,"Full index did not recover");
            pages+=reconcile(session,ledger,id,slots);
            var tail=session.receipts(slots-1,1);
            try { ledger.reconcile(new ReceiptPage(id,slots-1,1,List.of(),null,tail.revision()),slots-1,1,0);
                throw new IllegalStateException("Missing historical ACK accepted"); }
            catch(TransferException expected) { require(expected.code()==ErrorCode.UNKNOWN_COMMIT,"Wrong reconciliation rejection"); }
            ByteBuffer bytes=ByteBuffer.allocate(65536); long offset=0;
            while(offset<slots) {
                bytes.clear(); int n=session.readPersisted(offset,bytes); require(n>0,"Missing payload"); bytes.flip();
                for(int j=0;j<n;j++) require(bytes.get()==(byte)(offset+j),"Payload mismatch"); offset+=n;
            }
            bytes.clear(); require(session.readPersisted(offset,bytes)==-1,"Unexpected payload suffix");
        }
        try(var json=new JsonFactory().createGenerator(output.resolve("result.json").toFile(),com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            json.writeStartObject(); json.writeStringField("status","PASS"); json.writeStringField("scope","synthetic-storage-index-fixture");
            json.writeNumberField("slots",slots); json.writeNumberField("prefilledReceipts",slots-1);
            json.writeNumberField("metadataBudgetPerSide",budget); json.writeNumberField("sourceIndexBytes",49L*slots);
            json.writeNumberField("sinkIndexBytes",52L*slots); json.writeNumberField("journalBytes",68L*slots);
            json.writeNumberField("recoveryNanos",recoveryNanos); json.writeNumberField("fullReopenNanos",reopenNanos);
            json.writeNumberField("receiptPagesChecked",pages); json.writeNumberField("payloadBytesVerified",slots);
            json.writeNumberField("durationNanos",System.nanoTime()-started);
            json.writeNumberField("peakHeapPoolSum",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum());
            json.writeStringField("javaVersion",System.getProperty("java.version")); json.writeStringField("os",System.getProperty("os.name"));
            json.writeNumberField("processors",Runtime.getRuntime().availableProcessors()); json.writeEndObject();
        }
        System.out.println("PASS slots="+slots);
    }
}
