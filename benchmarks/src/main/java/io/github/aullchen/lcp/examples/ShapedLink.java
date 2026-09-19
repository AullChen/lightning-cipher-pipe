package io.github.aullchen.lcp.examples;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Bounded byte-stream proxy. TLS remains end-to-end; counts exclude TCP/IP headers and TCP retransmission. */
final class ShapedLink implements AutoCloseable {
    static final int CONNECTIONS = 16, QUEUE = 32, BLOCK = 16384;
    private final ServerSocket listener;
    private final int target;
    private final String scenario;
    private final boolean training;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(CONNECTIONS);
    private final ExecutorService workers = Executors.newFixedThreadPool(4*CONNECTIONS);
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor();
    private final Thread acceptor;
    private final long started = System.nanoTime();
    private final long[] next = new long[2];
    private volatile boolean closed, outage;
    final AtomicLong upstream = new AtomicLong(), downstream = new AtomicLong();
    final AtomicInteger disconnects = new AtomicInteger();
    ShapedLink(int target, String scenario, boolean training) throws IOException {
        if (!Set.of("stable","step","rtt","sink","outage").contains(scenario)) throw new IllegalArgumentException("Unknown trace");
        this.target=target; this.scenario=scenario; this.training=training;
        listener = new ServerSocket(); listener.bind(new InetSocketAddress("127.0.0.1",0));
        acceptor = new Thread(this::accept,"benchmark-link"); acceptor.start();
        if (scenario.equals("outage")) events.schedule(() -> {
            outage=true; disconnects.incrementAndGet(); sockets.forEach(ShapedLink::quietClose);
            events.schedule(() -> outage=false,300,TimeUnit.MILLISECONDS);
        },training?700:1000,TimeUnit.MILLISECONDS);
    }
    int port() { return listener.getLocalPort(); }
    private void accept() {
        while (!closed) try {
            Socket source=listener.accept();
            if (outage || !slots.tryAcquire()) { quietClose(source); continue; }
            Socket destination=new Socket();
            try {
                destination.connect(new InetSocketAddress("127.0.0.1",target),5000);
                source.setTcpNoDelay(true); destination.setTcpNoDelay(true);
                source.setSoTimeout(30000); destination.setSoTimeout(30000);
                sockets.add(source); sockets.add(destination);
                var exits=new AtomicInteger(4);
                Runnable done=() -> { if (exits.decrementAndGet()==0) {
                    quietClose(source); quietClose(destination); sockets.remove(source); sockets.remove(destination); slots.release();
                }};
                pipe(source,destination,0,done); pipe(destination,source,1,done);
            } catch (IOException e) { quietClose(source); quietClose(destination); slots.release(); }
        } catch (IOException e) { if (!closed) throw new UncheckedIOException(e); }
    }
    private record Packet(byte[] bytes,long received) {}
    private void pipe(Socket input,Socket output,int direction,Runnable done) {
        var queue=new ArrayBlockingQueue<Packet>(QUEUE);
        workers.submit(() -> {
            try {
                byte[] buffer=new byte[BLOCK]; int n;
                while ((n=input.getInputStream().read(buffer))!=-1) {
                    if (!enqueue(queue,new Packet(Arrays.copyOf(buffer,n),System.nanoTime()),input,output)) return;
                }
                enqueue(queue,new Packet(new byte[0],System.nanoTime()),input,output);
            } catch (IOException | InterruptedException e) { quietClose(input); quietClose(output); }
            finally { done.run(); }
        });
        workers.submit(() -> {
            try {
                while (!closed) {
                    Packet packet=queue.poll(1,TimeUnit.SECONDS);
                    if (packet==null) { if (input.isClosed()) break; continue; }
                    if (packet.bytes.length==0) { output.shutdownOutput(); break; }
                    long due=due(direction,packet);
                    while (System.nanoTime()<due) {
                        if (closed || Thread.currentThread().isInterrupted()) return;
                        LockSupport.parkNanos(Math.min(10_000_000,due-System.nanoTime()));
                    }
                    output.getOutputStream().write(packet.bytes);
                    (direction==0?upstream:downstream).addAndGet(packet.bytes.length);
                }
            } catch (IOException | InterruptedException e) { quietClose(input); quietClose(output); }
            finally { done.run(); }
        });
    }
    private boolean enqueue(ArrayBlockingQueue<Packet> queue,Packet packet,Socket input,Socket output) throws InterruptedException {
        while (!closed && !input.isClosed() && !output.isClosed()) {
            if (queue.offer(packet,100,TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }
    private synchronized long due(int direction,Packet packet) {
        long now=System.nanoTime(); double seconds=(now-started)/1e9;
        long latency=scenario.equals("rtt")?(training?40_000_000:60_000_000):0;
        if (!scenario.equals("step")) return packet.received+latency;
        double begin=training?.5:.7, end=training?1.5:2.3;
        long rate=(seconds>=begin && seconds<end ? 8L : 64L)*1024*1024;
        // A bounded 1 MiB burst avoids turning sub-millisecond JVM sleeps into an accidental low rate.
        next[direction]=Math.max(now,next[direction])+packet.bytes.length*1_000_000_000L/rate;
        return Math.max(packet.received+latency,next[direction]-1048576L*1_000_000_000L/rate);
    }
    private static void quietClose(Socket socket) { try { socket.close(); } catch (IOException ignored) {} }
    public void close() throws IOException {
        closed=true; listener.close(); events.shutdownNow(); sockets.forEach(ShapedLink::quietClose); workers.shutdownNow();
        try { acceptor.join(5000); if (!workers.awaitTermination(5,TimeUnit.SECONDS)) throw new IOException("Proxy workers did not exit"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
