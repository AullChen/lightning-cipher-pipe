package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.core.protocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SourceControlTest extends StorageTestSupport {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void processExitLeavesOnlySmallIntentAndAcceptedFacts(boolean accepted) throws Exception {
        var transfer=FileSinkTest.transfer();
        var record=new SourceControlCodec.Record("https://localhost:9443/lcp-stream/v1/transfers",transfer.request(),accepted ? transfer : null,null);
        Path input=root.resolve("intent.cbor"), output=root.resolve("control.cbor"); Files.write(input,SourceControlCodec.encode(record));
        String binary=System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"-cp",System.getProperty("java.class.path"),
                Crash.class.getName(),input.toString(),output.toString()).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try { assertTrue(child.waitFor(10,TimeUnit.SECONDS)); assertEquals(72,child.exitValue(),Files.readString(root.resolve("child.log"))); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5,TimeUnit.SECONDS); } }
        try (var control=new SourceControl(output)) {
            assertEquals(record.request(),control.read().request()); assertEquals(record.accepted(),control.read().accepted());
            assertTrue(Files.size(output)<4096); assertThrows(IOException.class,() -> new SourceControl(output));
        }
    }
    @Test void unknownControlFileIsNotOverwritten() throws Exception {
        Path file=root.resolve("unknown.cbor"); Files.write(file,new byte[]{1,2,3});
        try (var control=new SourceControl(file)) {
            assertThrows(IOException.class,control::read);
            assertThrows(IOException.class,() -> control.save(new SourceControlCodec.Record("https://localhost/transfers",FileSinkTest.transfer().request(),null,null)));
        }
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file));
    }
    public static class Crash {
        public static void main(String[] args) throws Exception {
            try (var control=new SourceControl(Path.of(args[1]))) {
                control.save(SourceControlCodec.decode(Files.readAllBytes(Path.of(args[0])))); Runtime.getRuntime().halt(72);
            }
        }
    }
}
