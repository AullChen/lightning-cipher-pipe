package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;
import static org.junit.jupiter.api.Assertions.*;

class FileSinkReplayTest extends StorageTestSupport {
    private AcceptedTransfer transfer(int slots) {
        var old=FileSinkTest.transfer(); var r=old.request(); var p=r.policy();
        var limits=new Limits(4096,4,slots,4L*slots,1);
        var request=new OpenRequest(r.transferId(),r.routeId(),r.sourceNodeId(),r.targetNodeId(),r.sourceChallenge(),
                r.sourceKeyId(),r.sourcePublicKeyHash(),r.targetKeyId(),r.targetPublicKeyHash(),r.compressionOffers(),p,limits,r.createdAt(),r.expiresAt());
        var a=new Accepted(request.transferId(),old.response().accepted().targetChallenge(),request.transferId().toString(),0,p,limits,request.expiresAt());
        return new AcceptedTransfer(request,new OpenResponse(a,MetadataCodec.openRequestHash(request),
                MetadataCodec.bindingHash(request,a,old.sourceTlsSpki(),old.targetTlsSpki())),old.sourceTlsSpki(),old.targetTlsSpki());
    }
    private void fixture(AcceptedTransfer t, List<Receipt> records, byte[] payload) throws Exception {
        try(var sink=new FileSink(root,52L*t.request().limits().maxChunks())) { sink.open(t); }
        Path dir=root.resolve(t.request().transferId().toString());
        Files.write(dir.resolve("payload.bin"),payload);
        var bytes=ByteBuffer.allocate(68*records.size());
        for(var r:records) {
            int start=bytes.position();
            bytes.putInt(0x4c435052).putInt(1).putLong(r.chunkIndex()).putLong(r.offset()).putLong(r.plainLength()).put(r.payloadHash().bytes());
            var crc=new CRC32C(); crc.update(bytes.array(),start,64); bytes.putInt((int)crc.getValue());
        }
        Files.write(dir.resolve("receipts.log"),bytes.array());
    }
    @ParameterizedTest @ValueSource(ints={128,256,512})
    void reversedReplayHasBoundedIndexWorkAndPreservesReceipts(int count) throws Exception {
        var t=transfer(count+1); var id=t.request().transferId();
        var expected=new Receipt[count]; var records=new ArrayList<Receipt>(); byte[] payload=new byte[count*2];
        for(int i=0;i<count;i++) {
            payload[2*i]=(byte)i; payload[2*i+1]=(byte)(i>>>8);
            int index=i*37%count; // Offset order must not be confused with chunk-index order.
            expected[index]=new Receipt(id,index,2L*i,2,MetadataCodec.hash(Arrays.copyOfRange(payload,2*i,2*i+2)));
            records.add(expected[index]);
        }
        Collections.reverse(records); fixture(t,records,payload);
        var moved=new AtomicLong(); var forces=new AtomicLong();
        try(var sink=new FileSink(root,52L*(count+1),new FileSink.Io() {
            @Override public void indexEntriesMoved(int n) { moved.addAndGet(n); }
            @Override public void force(FileChannel c) throws java.io.IOException { forces.incrementAndGet(); c.force(true); }
        })) {
            var s=sink.recover(id);
            assertEquals(State.OPEN,s.state().state()); assertEquals(count,s.state().committedChunks());
            assertEquals(count*2L,s.state().committedPlainBytes()); assertEquals(2,forces.get());
            for(int from=0;from<count;from+=256) {
                var page=s.receipts(from,Math.min(256,count-from));
                assertEquals(Arrays.asList(Arrays.copyOfRange(expected,from,Math.min(count,from+256))),page.entries());
            }
            var actual=ByteBuffer.allocate(payload.length); assertEquals(payload.length,s.readPersisted(0,actual)); assertArrayEquals(payload,actual.array());
            long work=moved.get(), bound=4L*count*(32-Integer.numberOfLeadingZeros(count));
            System.out.println("reverse replay records="+count+" index entries moved="+work+" bound="+bound);
            assertTrue(work<=bound,"Recovery index work must not grow quadratically");
            assertThrows(TransferException.class,() -> s.commit(FileSinkTest.chunk(count,1,(byte)7)));
            var last=FileSinkTest.chunk(count,2L*count,(byte)7);
            assertEquals(s.commit(last),s.commit(last)); assertEquals(count+1,s.state().committedChunks());
        }
    }
    @ParameterizedTest @ValueSource(strings={"overlap","duplicate","bounds"})
    void malformedReplayFreezesBeforePublishingReceipts(String corruption) throws Exception {
        var t=transfer(8); var id=t.request().transferId(); var hash=MetadataCodec.hash(new byte[]{0,0});
        var records=new ArrayList<Receipt>(List.of(new Receipt(id,3,6,2,hash),new Receipt(id,1,2,2,hash),new Receipt(id,0,0,2,hash)));
        records.add(switch(corruption) {
            case "overlap" -> new Receipt(id,2,3,2,hash);
            case "duplicate" -> new Receipt(id,1,4,2,hash);
            default -> new Receipt(id,8,4,2,hash);
        });
        fixture(t,records,new byte[8]);
        try(var sink=new FileSink(root,52L*8)) {
            var s=sink.recover(id); assertEquals(State.RECOVERY_REQUIRED,s.state().state());
            assertEquals(0,s.state().committedChunks()); assertThrows(TransferException.class,() -> s.receipts(0,8));
        }
    }
}
