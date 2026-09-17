package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.TransferVerifier;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.aullchen.lcp.examples.FileSinkTest.*;

class LifecycleStorageTest extends StorageTestSupport {
    static FinishManifest finish(AcceptedTransfer t) {
        var tree=new MerkleFrontier(); tree.append(0,0,2,MetadataCodec.hash(new byte[]{1,2}));
        return new FinishManifest(t.request().transferId(),t.response().bindingHash(),UUID.randomUUID(),1,2,tree.root());
    }
    static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5,TimeUnit.SECONDS)) throw new IOException("Barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    @Test void cancelClosesAdmissionThenWaitsForTheActualWriter() throws Exception {
        var writing=new CountDownLatch(1); var release=new CountDownLatch(1); var cancelling=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try (var sink=new FileSink(root,4096,new FileSink.Io(){
            public void after(FileSink.Boundary stage) throws IOException { if (stage==FileSink.Boundary.PAYLOAD_WRITTEN) { writing.countDown(); await(release); } }
            public void cancelling() { cancelling.countDown(); }
        })) {
            var t=transfer(2); var session=sink.open(t);
            var writer=pool.submit(() -> session.commit(chunk(0,0,(byte)1,(byte)2)));
            try {
                await(writing); var command=new CancelCommand(t.request().transferId(),UUID.randomUUID(),t.response().bindingHash());
                var cancel=pool.submit(() -> session.cancel(command)); await(cancelling);
                assertFalse(cancel.isDone());
                assertEquals(ErrorCode.STATE_CONFLICT,assertThrows(TransferException.class,() -> session.commit(chunk(1,2,(byte)3))).code());
                release.countDown(); writer.get(3,TimeUnit.SECONDS);
                assertEquals(State.CANCELLED,cancel.get(3,TimeUnit.SECONDS).state());
                assertEquals(State.CANCELLED,session.cancel(command).state()); assertEquals(1,session.state().committedChunks());
            } finally { release.countDown(); }
        } finally { pool.shutdownNow(); }
    }
    @Test void cancelWinsVerificationCompletionWithoutDeletingOutput() throws Exception {
        var reading=new CountDownLatch(1); var release=new CountDownLatch(1); var cancelling=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try (var sink=new FileSink(root,4096,new FileSink.Io(){
            public int read(FileChannel channel,ByteBuffer bytes,long offset) throws IOException { reading.countDown(); await(release); return channel.read(bytes,offset); }
            public void cancelling() { cancelling.countDown(); }
        })) {
            var t=transfer(); var session=sink.open(t); session.commit(chunk(0,0,(byte)1,(byte)2)); var manifest=finish(t);
            var verification=pool.submit(() -> TransferVerifier.finish(session,manifest,Clock.systemUTC(),Duration.ofSeconds(5)));
            try {
                await(reading); var command=new CancelCommand(t.request().transferId(),UUID.randomUUID(),t.response().bindingHash());
                var cancel=pool.submit(() -> session.cancel(command)); await(cancelling); assertFalse(cancel.isDone());
                release.countDown(); assertEquals(State.CANCELLED,cancel.get(3,TimeUnit.SECONDS).state());
                assertThrows(ExecutionException.class,() -> verification.get(3,TimeUnit.SECONDS));
                assertNull(session.state().result()); assertEquals(manifest,session.state().finish());
                assertTrue(java.nio.file.Files.exists(root.resolve(t.request().transferId()+"/payload.bin")));
            } finally { release.countDown(); }
        } finally { pool.shutdownNow(); }
    }
    @Test void completedWinsLaterCancelAndSurvivesReopen() throws Exception {
        var t=transfer(); VerifiedResult result;
        try (var sink=new FileSink(root,4096)) {
            var session=sink.open(t); session.commit(chunk(0,0,(byte)1,(byte)2));
            result=TransferVerifier.finish(session,finish(t),Clock.systemUTC(),Duration.ofSeconds(5));
            assertEquals(result,session.cancel(new CancelCommand(t.request().transferId(),UUID.randomUUID(),t.response().bindingHash())).result());
        }
        try (var sink=new FileSink(root,4096)) { assertEquals(result,sink.recover(t.request().transferId()).state().result()); }
    }
    @Test void cancellationTimeoutFreezesWhileTheWriterStillOwnsItsRange() throws Exception {
        var writing=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor();
        try (var sink=new FileSink(root,4096,new FileSink.Io(){
            public void after(FileSink.Boundary stage) throws IOException { if (stage==FileSink.Boundary.PAYLOAD_WRITTEN) { writing.countDown(); await(release); } }
        },Duration.ofMillis(30))) {
            var t=transfer(); var session=sink.open(t); var writer=pool.submit(() -> session.commit(chunk(0,0,(byte)1)));
            try {
                await(writing);
                assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,() -> session.cancel(new CancelCommand(t.request().transferId(),UUID.randomUUID(),t.response().bindingHash()))).code());
                assertFalse(writer.isDone()); assertEquals(State.RECOVERY_REQUIRED,session.state().state());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class,() -> writer.get(3,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void closeTimeoutKeepsRootLockUntilWriterActuallyExits() throws Exception {
        var writing=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor();
        var sink=new FileSink(root,4096,new FileSink.Io(){
            public void after(FileSink.Boundary stage) throws IOException { if (stage==FileSink.Boundary.PAYLOAD_WRITTEN) { writing.countDown(); await(release); } }
        },Duration.ofMillis(30));
        try {
            var session=sink.open(transfer()); var writer=pool.submit(() -> session.commit(chunk(0,0,(byte)1)));
            await(writing); assertThrows(IOException.class,sink::close); assertFalse(writer.isDone());
            assertThrows(IOException.class,() -> new FileSink(root,4096));
            release.countDown(); writer.get(3,TimeUnit.SECONDS); sink.close();
            try (var reopened=new FileSink(root,4096)) { assertNotNull(reopened); }
        } finally { release.countDown(); pool.shutdownNow(); sink.close(); }
    }
}
