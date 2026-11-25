package com.pocketvpnbridge;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;

/** Restricts incoming SOCKS clients to a directly connected LAN subnet. */
final class LanAccessPolicy {
    private LanAccessPolicy() {}

    static boolean isAllowed(InetAddress remote) {
        if (remote == null) return false;
        if (remote.isLoopbackAddress()) return true;

        boolean sawLanInterface = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface network = interfaces.nextElement();
                    if (!isLanInterface(network)) continue;

                    for (InterfaceAddress address : network.getInterfaceAddresses()) {
                        InetAddress local = address.getAddress();
                        if (local == null || local.isLoopbackAddress()) continue;
                        sawLanInterface = true;
                        if (sameSubnet(remote, local, address.getNetworkPrefixLength())) {
                            return true;
                        }
                    }
                }
            }
        } catch (SocketException | SecurityException ignored) {
            // Fall through to the conservative private-address check below.
        }

        // Some Android OEMs hide interface prefixes. In that case allow only
        // well-known private/link-local ranges, never arbitrary public clients.
        return !sawLanInterface && isPrivateOrLocal(remote);
    }

    private static boolean isLanInterface(NetworkInterface network) throws SocketException {
        if (network == null || !network.isUp() || network.isLoopback() || network.isVirtual()) {
            return false;
        }
        String name = network.getName();
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return !(n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp")
                || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("clat")
                || n.startsWith("wg") || n.startsWith("ipsec") || n.startsWith("xfrm")
                || n.startsWith("vti") || n.startsWith("gre") || n.startsWith("pdp")
                || n.startsWith("wwan") || n.contains("vpn") || n.startsWith("dummy")
                || n.equals("lo"));
    }

    private static boolean sameSubnet(InetAddress a, InetAddress b, int prefixLength) {
        byte[] left = a.getAddress();
        byte[] right = b.getAddress();
        if (left.length != right.length) return false;
        int maxBits = left.length * 8;
        if (prefixLength <= 0) return false;
        int prefix = Math.min(prefixLength, maxBits);
        int wholeBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (left[i] != right[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xFF << (8 - remainingBits);
        return (left[wholeBytes] & mask) == (right[wholeBytes] & mask);
    }

    private static boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return a == 10 || (a == 172 && second >= 16 && second <= 31)
                    || (a == 192 && second == 168)
                    || (a == 169 && second == 254);
        }
        if (b.length == 16) {
            // IPv6 unique-local fc00::/7.
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
