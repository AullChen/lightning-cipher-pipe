package io.github.aullchen.lcp.examples;

import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

abstract class StorageTestSupport {
    @TempDir(factory = StorageTemp.class, cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path root;
    @org.junit.jupiter.api.AfterEach void cleanup() throws Exception {
        Path owned = root.toAbsolutePath().normalize();
        assertTrue(owned.startsWith(Path.of("target", "storage-tests").toAbsolutePath().normalize()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(owned)) {
            try (var paths = Files.walk(owned)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (java.io.IOException e) {
                if (System.nanoTime() >= deadline) throw e;
                Thread.sleep(25);
            }
        }
    }
    public static class StorageTemp implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
                org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
            return Files.createTempDirectory(Files.createDirectories(Path.of("target", "storage-tests")), "sink-");
        }
    }
}
