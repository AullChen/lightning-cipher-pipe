package io.github.aullchen.lcp.examples;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;

/** Atomic replacement of an already forced temporary file. */
final class AtomicFiles {
    private AtomicFiles() {}
    @FunctionalInterface interface Move { void replace(Path source, Path target) throws IOException; }
    static void replace(Path source, Path target) throws IOException {
        replace(source,target,(s,t) -> Files.move(s,t,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
    }
    static void replace(Path source, Path target, Move move) throws IOException {
        for (int attempt=0;;attempt++) {
            try { move.replace(source,target); return; }
            catch (AccessDeniedException denied) {
                // Sharing denial can outlive a reader briefly. Never fall back to non-atomic I/O.
                if (attempt==4) throw denied;
                try { Thread.sleep(25); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var interrupted=new InterruptedIOException("Atomic replacement interrupted");
                    interrupted.initCause(e); interrupted.addSuppressed(denied); throw interrupted;
                }
            }
        }
    }
}
