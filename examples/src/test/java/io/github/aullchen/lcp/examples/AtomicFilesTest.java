package io.github.aullchen.lcp.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesTest {
    @TempDir Path root;
    @Test void temporaryDenialRetriesTheSameCompleteFile() throws Exception {
        Path old=Files.write(root.resolve("state"),new byte[]{1});
        Path next=Files.write(root.resolve("temp"),new byte[]{2});
        var attempts=new AtomicInteger();
        AtomicFiles.replace(next,old,(s,t) -> {
            assertArrayEquals(new byte[]{1},Files.readAllBytes(t));
            assertArrayEquals(new byte[]{2},Files.readAllBytes(s));
            if(attempts.incrementAndGet()==1) throw new AccessDeniedException(t.toString());
            Files.move(s,t,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        });
        assertEquals(2,attempts.get()); assertArrayEquals(new byte[]{2},Files.readAllBytes(old));
        assertFalse(Files.exists(next));
    }
    @Test void persistentDenialStopsAndPreservesBothFiles() throws Exception {
        Path old=Files.write(root.resolve("state"),new byte[]{1});
        Path next=Files.write(root.resolve("temp"),new byte[]{2});
        var attempts=new AtomicInteger();
        assertThrows(AccessDeniedException.class,() -> AtomicFiles.replace(next,old,(s,t) -> {
            attempts.incrementAndGet(); throw new AccessDeniedException(t.toString());
        }));
        assertEquals(5,attempts.get());
        assertArrayEquals(new byte[]{1},Files.readAllBytes(old)); assertArrayEquals(new byte[]{2},Files.readAllBytes(next));
    }
    @Test void unsupportedAtomicMoveDoesNotRetryOrFallBack() {
        var attempts=new AtomicInteger();
        assertThrows(AtomicMoveNotSupportedException.class,() -> AtomicFiles.replace(root.resolve("temp"),root.resolve("state"),(s,t) -> {
            attempts.incrementAndGet(); throw new AtomicMoveNotSupportedException(s.toString(),t.toString(),"unsupported");
        }));
        assertEquals(1,attempts.get());
    }
    @Test void interruptedWaitStopsAndPreservesInterrupt() {
        var attempts=new AtomicInteger();
        try {
            assertThrows(InterruptedIOException.class,() -> AtomicFiles.replace(root.resolve("temp"),root.resolve("state"),(s,t) -> {
                attempts.incrementAndGet(); Thread.currentThread().interrupt(); throw new AccessDeniedException(t.toString());
            }));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1,attempts.get());
        } finally { Thread.interrupted(); }
    }
}
