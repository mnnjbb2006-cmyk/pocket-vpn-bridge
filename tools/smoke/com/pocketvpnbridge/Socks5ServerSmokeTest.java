package com.pocketvpnbridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Standalone JVM smoke test for auth + IPv4 TCP CONNECT + bidirectional relay. */
public final class Socks5ServerSmokeTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket echoServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread echoThread = new Thread(() -> {
                try (Socket accepted = echoServer.accept()) {
                    byte[] buffer = new byte[64];
                    int n = accepted.getInputStream().read(buffer);
                    if (n > 0) accepted.getOutputStream().write(buffer, 0, n);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }, "smoke-echo");
            echoThread.setDaemon(true);
            echoThread.start();

            Socks5Server proxy = new Socks5Server();
            proxy.start(0, "android", "smoke-password", stats -> {});
            int proxyPort = proxy.getPort();
            try {
                verifyNoAuthRejected(proxyPort);
                verifyBadPasswordRejected(proxyPort);
                verifyConnectAndRelay(proxyPort, echoServer.getLocalPort());
                if (proxy.getStats().authenticatedConnections != 1) {
                    throw new AssertionError("Expected one authenticated connection");
                }
                System.out.println("PASS: SOCKS5 password negotiation, rejected credentials, TCP CONNECT and relay");
            } finally {
                proxy.stop();
            }
        }
    }

    private static void verifyNoAuthRejected(int proxyPort) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(new byte[] {0x05, 0x01, 0x00});
            out.flush();
            if (in.readUnsignedByte() != 0x05 || in.readUnsignedByte() != 0xFF) {
                throw new AssertionError("Unauthenticated SOCKS method was not rejected");
            }
        }
    }

    private static void verifyBadPasswordRejected(int proxyPort) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write(new byte[] {0x05, 0x01, 0x02});
            out.flush();
            if (in.readUnsignedByte() != 0x05 || in.readUnsignedByte() != 0x02) {
                throw new AssertionError("Username/password method was not selected");
            }
            byte[] user = "android".getBytes(StandardCharsets.UTF_8);
            byte[] pass = "wrong-password".getBytes(StandardCharsets.UTF_8);
            out.writeByte(0x01);
            out.writeByte(user.length);
            out.write(user);
            out.writeByte(pass.length);
            out.write(pass);
            out.flush();
            if (in.readUnsignedByte() != 0x01 || in.readUnsignedByte() != 0x01) {
                throw new AssertionError("Incorrect password was accepted");
            }
        }
    }

    private static void verifyConnectAndRelay(int proxyPort, int echoPort) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            out.write(new byte[] {0x05, 0x01, 0x02});
            out.flush();
            if (in.readUnsignedByte() != 0x05 || in.readUnsignedByte() != 0x02) {
                throw new AssertionError("Authentication negotiation failed");
            }

            byte[] user = "android".getBytes(StandardCharsets.UTF_8);
            byte[] pass = "smoke-password".getBytes(StandardCharsets.UTF_8);
            out.writeByte(0x01);
            out.writeByte(user.length);
            out.write(user);
            out.writeByte(pass.length);
            out.write(pass);
            out.flush();
            if (in.readUnsignedByte() != 0x01 || in.readUnsignedByte() != 0x00) {
                throw new AssertionError("Valid credentials were rejected");
            }

            out.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1});
            out.writeShort(echoPort);
            out.flush();
            if (in.readUnsignedByte() != 0x05 || in.readUnsignedByte() != 0x00) {
                throw new AssertionError("SOCKS CONNECT failed");
            }
            in.readUnsignedByte(); // RSV
            int atyp = in.readUnsignedByte();
            int addressLength = atyp == 0x01 ? 4 : atyp == 0x04 ? 16 : -1;
            if (addressLength < 0) throw new AssertionError("Unexpected reply address type");
            in.skipNBytes(addressLength + 2L);

            byte[] message = "bridge-test".getBytes(StandardCharsets.UTF_8);
            out.write(message);
            out.flush();
            byte[] echoed = new byte[message.length];
            in.readFully(echoed);
            if (!java.util.Arrays.equals(message, echoed)) {
                throw new AssertionError("TCP relay changed payload bytes");
            }
        }
    }
}
