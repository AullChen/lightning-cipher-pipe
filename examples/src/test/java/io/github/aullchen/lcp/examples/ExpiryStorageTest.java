package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExpiryStorageTest extends StorageTestSupport {
    @Test void expiryDrainsAdmittedWriteBeforePublishingFailure() throws Exception {
        var t=FileSinkTest.transfer(); var expiring=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try (var sink=new FileSink(root,4096,new FileSink.Io(){ public void expiring(){ expiring.countDown(); } })) {
            var session=sink.open(t);
            try (var reservation=session.reserve(FileSinkTest.chunk(0,0,(byte)7))) {
                var expired=pool.submit(() -> { session.expire(t.request().expiresAt()); return session.state(); });
                assertTrue(expiring.await(3,TimeUnit.SECONDS)); assertFalse(expired.isDone());
                assertThrows(TransferException.class,()->session.reserve(FileSinkTest.chunk(1,1,(byte)8)));
                reservation.commit();
                var state=expired.get(3,TimeUnit.SECONDS);
                assertEquals(State.FAILED,state.state()); assertEquals(ErrorCode.EXPIRED,state.error());
                assertEquals(1,state.committedChunks()); assertEquals(1,state.committedPlainBytes());
            }
        } finally { pool.shutdownNow(); }
        try (var sink=new FileSink(root,4096)) {
            var session=sink.recover(t.request().transferId());
            assertEquals(State.FAILED,session.state().state()); assertEquals(1,session.receipts(0,1).entries().size());
        }
    }
    @Test void drainTimeoutFreezesInsteadOfClaimingExpiry() throws Exception {
        var t=FileSinkTest.transfer();
        try (var sink=new FileSink(root,4096,new FileSink.Io(){},Duration.ofMillis(20))) {
            var session=sink.open(t);
            try (var reservation=session.reserve(FileSinkTest.chunk(0,0,(byte)1))) {
                assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,
                        ()->session.expire(t.request().expiresAt())).code());
                assertEquals(State.RECOVERY_REQUIRED,session.state().state());
                assertThrows(TransferException.class,()->session.reserve(FileSinkTest.chunk(1,1,(byte)2)));
            }
        }
    }
    @Test void startupFinalizesExpiredPersistedTask() throws Exception {
        var t=FileSinkTest.transfer();
        try (var sink=new FileSink(root,4096)) { sink.open(t).commit(FileSinkTest.chunk(0,0,(byte)3)); }
        try (var sink=new FileSink(root,4096)) {
            sink.expire(t.request().expiresAt());
            var session=sink.recover(t.request().transferId());
            assertEquals(State.FAILED,session.state().state()); assertEquals(ErrorCode.EXPIRED,session.state().error());
            assertEquals(1,session.state().committedChunks());
        }
    }
}
