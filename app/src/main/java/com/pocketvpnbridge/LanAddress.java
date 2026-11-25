package com.pocketvpnbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;

final class LanAddress {
    private LanAddress() {}

    static String currentIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!isLikelyLan(network)) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String value = address.getHostAddress();
                    if (address.isSiteLocalAddress()) return value;
                    if (!address.isLinkLocalAddress() && fallback == null) fallback = value;
                }
            }
        } catch (SocketException | SecurityException ignored) {
            return null;
        }
        return fallback;
    }

    private static boolean isLikelyLan(NetworkInterface network) throws SocketException {
        if (network == null || !network.isUp() || network.isLoopback() || network.isVirtual()) return false;
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
}
