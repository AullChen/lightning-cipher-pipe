package io.github.aullchen.lcp.examples;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ShapedLinkTest {
    @Test void boundedProxyPreservesByteStreamAndCountsBothDirections() throws Exception {
        byte[] data=new byte[100000]; new java.util.Random(42).nextBytes(data);
        var worker=Executors.newSingleThreadExecutor();
        try (var target=new ServerSocket(0,1,InetAddress.getLoopbackAddress()); var link=new ShapedLink(target.getLocalPort(),"stable",false)) {
            var echo=worker.submit(() -> {
                try (var socket=target.accept()) {
                    socket.setSoTimeout(5000); byte[] received=socket.getInputStream().readNBytes(data.length);
                    assertArrayEquals(data,received); socket.getOutputStream().write(received);
                } return null;
            });
            try (var source=new Socket("127.0.0.1",link.port())) {
                source.setSoTimeout(5000); source.getOutputStream().write(data);
                assertArrayEquals(data,source.getInputStream().readNBytes(data.length));
            }
            echo.get(5,TimeUnit.SECONDS);
            assertEquals(data.length,link.upstream.get()); assertEquals(data.length,link.downstream.get());
        } finally { worker.shutdownNow(); }
    }
}
