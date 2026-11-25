package com.pocketvpnbridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small RFC 1928/1929 SOCKS5 server. It implements username/password auth and
 * TCP CONNECT. It deliberately does not create/protect a VPN socket: outbound
 * Socket connections use Android's normal process routing, including an
 * already-connected system VPN when that VPN includes this app.
 *
 * This class has no Android dependencies and can be smoke-tested on a JVM.
 */
final class Socks5Server {
    interface Listener {
        void onStats(Stats stats);
    }

    static final class Stats {
        final int activeConnections;
        final long bytesFromClients;
        final long bytesToClients;
        final long authenticatedConnections;

        Stats(int active, long up, long down, long total) {
            activeConnections = active;
            bytesFromClients = up;
            bytesToClients = down;
            authenticatedConnections = total;
        }
    }

    private static final int MAX_CONNECTIONS = 32;
    private static final int HANDSHAKE_TIMEOUT_MS = 25_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int BUFFER_SIZE = 16 * 1024;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong bytesFromClients = new AtomicLong(0);
    private final AtomicLong bytesToClients = new AtomicLong(0);
    private final AtomicLong authenticatedConnections = new AtomicLong(0);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workers;
    private volatile Thread acceptThread;
    private volatile Listener listener;
    private volatile byte[] expectedUsername;
    private volatile byte[] expectedPassword;
    private volatile int boundPort = -1;

    synchronized void start(int port, String username, String password, Listener callback)
            throws IOException {
        if (running.get()) throw new IllegalStateException("Proxy is already running");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");

        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        if (userBytes.length < 1 || userBytes.length > 255 || passBytes.length < 1 || passBytes.length > 255) {
            throw new IllegalArgumentException("SOCKS5 username and password must each be 1–255 UTF-8 bytes");
        }

        expectedUsername = userBytes;
        expectedPassword = passBytes;
        listener = callback;
        workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "PocketVPN-client");
            thread.setDaemon(true);
            return thread;
        });

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("0.0.0.0", port), 64);
        serverSocket = server;
        boundPort = server.getLocalPort();
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "PocketVPN-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        notifyStats();
    }

    synchronized void stop() {
        if (!running.getAndSet(false)) return;

        ServerSocket listenerSocket = serverSocket;
        serverSocket = null;
        if (listenerSocket != null) {
            try { listenerSocket.close(); } catch (IOException ignored) {}
        }

        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        openSockets.clear();

        ExecutorService executor = workers;
        workers = null;
        if (executor != null) executor.shutdownNow();

        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        boundPort = -1;
        notifyStats();
    }

    boolean isRunning() {
        return running.get();
    }

    int getPort() {
        return boundPort;
    }

    Stats getStats() {
        return new Stats(activeConnections.get(), bytesFromClients.get(),
                bytesToClients.get(), authenticatedConnections.get());
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client = null;
            try {
                ServerSocket current = serverSocket;
                if (current == null) break;
                client = current.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

                if (!LanAccessPolicy.isAllowed(client.getInetAddress()) || !connectionSlots.tryAcquire()) {
                    closeQuietly(client);
                    continue;
                }

                openSockets.add(client);
                ExecutorService executor = workers;
                if (executor == null || executor.isShutdown()) {
                    connectionSlots.release();
                    openSockets.remove(client);
                    closeQuietly(client);
                    break;
                }

                final Socket accepted = client;
                try {
                    executor.execute(() -> handleClient(accepted));
                } catch (RuntimeException rejected) {
                    openSockets.remove(accepted);
                    closeQuietly(accepted);
                    connectionSlots.release();
                }
            } catch (SocketException closed) {
                if (running.get()) sleepBriefly();
                else break;
            } catch (IOException error) {
                if (client != null) {
                    openSockets.remove(client);
                    closeQuietly(client);
                }
                if (running.get()) sleepBriefly();
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        boolean counted = false;
        try {
            DataInputStream input = new DataInputStream(client.getInputStream());
            DataOutputStream output = new DataOutputStream(client.getOutputStream());

            if (!negotiateAuthentication(input, output)) return;
            if (!authenticate(input, output)) return;

            Target target = readConnectRequest(input, output);
            if (target == null) return;

            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            try {
                upstream.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            } catch (IOException connectError) {
                writeReply(output, 0x05); // Connection refused / could not connect.
                return;
            }
            openSockets.add(upstream);
            upstream.setSoTimeout(0);
            client.setSoTimeout(0);
            writeSuccessReply(output, upstream);

            activeConnections.incrementAndGet();
            authenticatedConnections.incrementAndGet();
            counted = true;
            notifyStats();
            relay(client, upstream);
        } catch (IOException | RuntimeException ignored) {
            // A disconnected or malformed client should not take down the listener.
        } finally {
            closeQuietly(client);
            openSockets.remove(client);
            if (upstream != null) {
                closeQuietly(upstream);
                openSockets.remove(upstream);
            }
            if (counted) {
                activeConnections.decrementAndGet();
                notifyStats();
            }
            connectionSlots.release();
        }
    }

    private boolean negotiateAuthentication(DataInputStream in, DataOutputStream out) throws IOException {
        int version = in.readUnsignedByte();
        int count = in.readUnsignedByte();
        boolean supportsPassword = false;
        for (int i = 0; i < count; i++) {
            if (in.readUnsignedByte() == 0x02) supportsPassword = true;
        }
        if (version != 0x05 || !supportsPassword) {
            out.write(new byte[] {0x05, (byte) 0xFF});
            out.flush();
            return false;
        }
        out.write(new byte[] {0x05, 0x02});
        out.flush();
        return true;
    }

    private boolean authenticate(DataInputStream in, DataOutputStream out) throws IOException {
        int version = in.readUnsignedByte();
        int userLength = in.readUnsignedByte();
        byte[] user = new byte[userLength];
        in.readFully(user);
        int passLength = in.readUnsignedByte();
        byte[] pass = new byte[passLength];
        in.readFully(pass);

        boolean valid = version == 0x01
                && MessageDigest.isEqual(user, expectedUsername)
                && MessageDigest.isEqual(pass, expectedPassword);
        out.write(new byte[] {0x01, (byte) (valid ? 0x00 : 0x01)});
        out.flush();
        return valid;
    }

    private Target readConnectRequest(DataInputStream in, DataOutputStream out) throws IOException {
        int version = in.readUnsignedByte();
        int command = in.readUnsignedByte();
        in.readUnsignedByte(); // Reserved.
        int addressType = in.readUnsignedByte();
        if (version != 0x05) {
            writeReply(out, 0x01);
            return null;
        }
        if (command != 0x01) {
            writeReply(out, 0x07); // Command not supported (UDP ASSOCIATE/BIND).
            return null;
        }

        String host;
        if (addressType == 0x01) {
            byte[] address = new byte[4];
            in.readFully(address);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else if (addressType == 0x04) {
            byte[] address = new byte[16];
            in.readFully(address);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else if (addressType == 0x03) {
            int length = in.readUnsignedByte();
            if (length == 0) {
                writeReply(out, 0x08);
                return null;
            }
            byte[] domain = new byte[length];
            in.readFully(domain);
            host = new String(domain, StandardCharsets.UTF_8);
        } else {
            writeReply(out, 0x08); // Address type not supported.
            return null;
        }

        int port = in.readUnsignedShort();
        if (port == 0) {
            writeReply(out, 0x01);
            return null;
        }
        return new Target(host, port);
    }

    private void writeSuccessReply(DataOutputStream out, Socket upstream) throws IOException {
        InetAddress local = upstream.getLocalAddress();
        byte[] address = local.getAddress();
        out.writeByte(0x05);
        out.writeByte(0x00);
        out.writeByte(0x00);
        out.writeByte(address.length == 16 ? 0x04 : 0x01);
        out.write(address);
        out.writeShort(upstream.getLocalPort());
        out.flush();
    }

    private void writeReply(DataOutputStream out, int replyCode) throws IOException {
        out.write(new byte[] {0x05, (byte) replyCode, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private void relay(Socket client, Socket upstream) throws IOException {
        CountDownLatch finished = new CountDownLatch(2);
        AtomicBoolean failed = new AtomicBoolean(false);
        ExecutorService executor = workers;
        if (executor == null) throw new SocketException("Proxy is stopping");

        executor.execute(() -> copy(client, upstream, true, finished, failed));
        executor.execute(() -> copy(upstream, client, false, finished, failed));
        try {
            finished.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Relay interrupted", interrupted);
        }
    }

    private void copy(Socket source, Socket destination, boolean fromClient,
                      CountDownLatch finished, AtomicBoolean failed) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                out.flush();
                if (fromClient) bytesFromClients.addAndGet(count);
                else bytesToClients.addAndGet(count);
            }
            try { destination.shutdownOutput(); } catch (IOException ignored) {}
        } catch (IOException error) {
            if (failed.compareAndSet(false, true)) {
                closeQuietly(source);
                closeQuietly(destination);
            }
        } finally {
            finished.countDown();
        }
    }

    private void notifyStats() {
        Listener callback = listener;
        if (callback != null) {
            try { callback.onStats(getStats()); } catch (RuntimeException ignored) {}
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static void sleepBriefly() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Target {
        final String host;
        final int port;
        Target(String host, int port) { this.host = host; this.port = port; }
    }
}
