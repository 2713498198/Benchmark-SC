package com.cryptobench.desktop;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** File ownership plus authenticated loopback IPC, independent of the web server. */
final class DesktopInstance implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Path descriptor;
    private final ServerSocket server;
    private final String token;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Function<String, String> handler = command -> "STARTING";

    private DesktopInstance(FileChannel channel, FileLock lock, Path directory) throws IOException {
        this.channel = channel;
        this.lock = lock;
        this.descriptor = directory.resolve("instance.properties");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        token = HexFormat.of().formatHex(random);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        try {
            DesktopPaths.rejectLinks(descriptor);
            Path temporary = Files.createTempFile(directory, "instance-", ".tmp");
            try {
                DesktopPaths.privateAccess(temporary, false);
                Properties properties = new Properties();
                properties.setProperty("port", String.valueOf(server.getLocalPort()));
                properties.setProperty("token", token);
                properties.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
                try (var output = Files.newOutputStream(temporary)) { properties.store(output, "CryptoBench private control channel"); }
                Files.move(temporary, descriptor, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            Thread.ofPlatform().daemon().name("desktop-control").start(this::serve);
        } catch (IOException exception) {
            server.close();
            throw exception;
        }
    }

    static DesktopInstance acquire(Path directory) throws IOException {
        DesktopPaths.rejectLinks(directory.resolve("instance.lock"));
        FileChannel channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { /* same JVM */ }
            if (lock == null) { channel.close(); return null; }
            // A nonempty locked range lets the MSI preflight detect ownership without
            // launching Java or relying on a stale descriptor/PID.
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{'1'}), 0);
            channel.force(true);
            return new DesktopInstance(channel, lock, directory);
        } catch (IOException | RuntimeException exception) {
            if (lock != null) lock.release();
            channel.close();
            throw exception;
        }
    }

    void onCommand(Function<String, String> handler) { this.handler = Objects.requireNonNull(handler); }

    private void serve() {
        while (!closed.get()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                // readUTF limits input size; this is deliberately not an HTTP endpoint.
                var input = new DataInputStream(socket.getInputStream());
                var output = new DataOutputStream(socket.getOutputStream());
                String supplied = input.readUTF();
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.UTF_8))) {
                    output.writeUTF("DENIED");
                } else {
                    String command = input.readUTF();
                    output.writeUTF(Set.of("open", "status", "stop").contains(command) ? handler.apply(command) : "INVALID");
                }
                output.flush();
            } catch (IOException | RuntimeException ignored) {
                // Untrusted or timed-out clients must not terminate the owner.
            }
        }
    }

    static String request(Path directory, String command, Duration timeout) throws IOException, InterruptedException {
        if (!Set.of("open", "status", "stop").contains(command)) throw new IllegalArgumentException("Unknown desktop command");
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            try {
                Path descriptor = directory.resolve("instance.properties");
                DesktopPaths.rejectLinks(descriptor);
                Properties properties = new Properties();
                try (var input = Files.newInputStream(descriptor)) { properties.load(input); }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(properties.getProperty("port"))), 1000);
                    socket.setSoTimeout(2000);
                    var output = new DataOutputStream(socket.getOutputStream());
                    output.writeUTF(properties.getProperty("token"));
                    output.writeUTF(command);
                    output.flush();
                    String result = new DataInputStream(socket.getInputStream()).readUTF();
                    if (!"STARTING".equals(result) || "status".equals(command)) return result;
                }
            } catch (IOException | IllegalArgumentException exception) {
                if (System.nanoTime() >= deadline) throw new IOException("本地实例未响应，请查看启动日志。", exception);
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IOException("应用仍在启动或等待首次管理员设置，请查看设置窗口和日志。");
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.close();
            Files.deleteIfExists(descriptor);
        } finally {
            try { lock.release(); } finally { channel.close(); }
        }
        // Never delete the lock inode: another process may already be waiting on it.
    }
}
