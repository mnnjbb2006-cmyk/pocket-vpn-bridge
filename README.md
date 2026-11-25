# Pocket VPN Bridge

An Android app that shares the phone's **already-connected VPN** with devices on the same local network through an authenticated SOCKS5 proxy.

## What it does

```text
Laptop browser / curl
        |
        | SOCKS5 over Wi-Fi (phone LAN IP : 1080)
        v
Pocket VPN Bridge on Android
        |
        | ordinary outbound TCP socket from this app
        v
Android's active network routing
        |
        v
Your existing Android VPN app / VPN tunnel
        |
        v
Internet
```

This app intentionally does **not** extend `VpnService`, call `VpnService.prepare()`, replace your VPN, or protect/bypass its own sockets. Android routes the proxy's ordinary outbound sockets according to the active VPN's rules. This is what lets it work alongside an existing VPN app.

### Important VPN setting

Most full-device VPNs route this app automatically. If your VPN uses per-app or split tunneling, make sure **Pocket VPN Bridge is included in the VPN**. Also enable the VPN provider's *Allow LAN*, *Local network access*, or equivalent setting if it has one. A VPN configured to exclude this app cannot carry the proxy's outgoing connections.

## Features

- SOCKS5 username/password authentication (RFC 1929); no unauthenticated mode.
- TCP `CONNECT` for IPv4, IPv6, and domain-name destinations.
- Domain names are resolved on the phone when the client sends a SOCKS5 domain name (for example, `socks5h://` in curl or Firefox's remote-DNS option).
- Listens on the phone's local interfaces, but accepts clients only from a directly connected LAN subnet (with a private/link-local fallback on devices that hide interface prefixes).
- Foreground service with an ongoing notification and a notification Stop action.
- Optional partial wake lock for screen-off reliability (consumes more battery).
- Random 22-character password generated on first launch; port and credentials can be changed while stopped.
- Active connection count and copyable address / curl command.
- Android 17 (API 37) local-network permission support.

## Protocol scope

This version supports SOCKS5 **TCP CONNECT**. It does not implement `UDP ASSOCIATE` or `BIND`. Web browsing, HTTPS, SSH, and TCP-based applications work through it. UDP-based applications, some games, voice/video traffic, and direct QUIC/HTTP/3 are not proxied by this version. Browsers normally fall back to TCP when configured to use a SOCKS proxy, but behavior depends on the browser.

## Requirements

- Android 8.0 (API 26) or later.
- A working VPN connection in another Android app.
- Phone and laptop on the same Wi-Fi/LAN. Guest Wi-Fi with client isolation will not work.
- For building: JDK 17+, Android SDK Platform 37, Android Build Tools 36.0.0, Gradle 9.6.0, and Android Gradle Plugin 9.4.0.

## Use it

1. Connect your preferred VPN on the phone.
2. Open Pocket VPN Bridge and grant **Local network access** when Android asks (Android 17 / API 37 and newer).
3. Leave the port at `1080`, or choose another unused port. Keep the generated password or replace it with your own.
4. Tap **Start Proxy**. Android shows an ongoing notification while it is running.
5. On the laptop, use the IP address shown in the app and the configured port, username, and password.

Example: if the phone shows `192.168.1.3:1080`, run this on Linux/macOS (replace the password):

```bash
curl --proxy socks5h://192.168.1.3:1080 \
  --proxy-user 'android:YOUR_PASSWORD' \
  https://api.ipify.org
```

The returned IP should be the VPN exit IP, not the laptop's normal public IP. `socks5h` asks curl to send the hostname to the SOCKS5 server for resolution through the phone.

### Firefox

Open **Settings → General → Network Settings → Settings…**. Choose **Manual proxy configuration**, enter the phone's LAN IP and port in **SOCKS Host**, select **SOCKS v5**, and enable **Proxy DNS when using SOCKS v5**. Firefox prompts for the username and password when it first connects.

### Other command-line applications

Configure their SOCKS5 proxy as:

- Host: the phone's LAN IPv4 address
- Port: `1080` (or your chosen port)
- Username/password: the values shown in the app
- Prefer remote DNS / SOCKS5 hostname mode when available

For curl, the `--proxy socks5h://...` form above is the reference test.

## Build the APK

Open this project folder in Android Studio with a JDK 17+ installation and Android SDK Platform 37 installed, then choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

Or use the included Gradle bootstrap script (it downloads the pinned Gradle 9.6.0 distribution on first use):

```bash
./gradlew :app:assembleDebug
```

On Windows, run `gradlew.bat :app:assembleDebug`. The Android SDK Platform 37 and Build Tools 36.0.0 must still be installed.

The installable debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the machine's automatically generated debug key. It is suitable for personal testing/sideloading, not for publishing to Google Play. A production release should be signed with your own private release key.

### Automated APK build on GitHub

A workflow is included at `.github/workflows/build-apk.yml`. Push this source folder to a GitHub repository, then run **Actions → Build Android APK → Run workflow**. The workflow builds `app-debug.apk` and publishes it as a downloadable workflow artifact.

## Test the protocol core

The SOCKS5 protocol implementation is plain Java and has a local smoke test that does not need the Android SDK:

```bash
./tools/test_core.sh
```

It tests mandatory authentication, rejection of an incorrect password, IPv4 TCP CONNECT, and bidirectional relay.

## Security notes

- This is a network proxy. Anyone who obtains its credentials and can reach the phone's LAN subnet may use the phone's VPN connection.
- SOCKS5 username/password authentication is **not encrypted** by the SOCKS5 protocol. Use this only on a trusted LAN; do not expose the port through router port forwarding or use it on an untrusted/public Wi-Fi network.
- The server requires authentication and checks that incoming clients are on a directly connected LAN subnet. The password is stored in the app's private preferences.
- Stop the proxy when you no longer need it. The ongoing Android notification has a Stop action.
- If a VPN has a kill switch, lockdown mode, or LAN-blocking feature, it may prevent local clients from reaching the proxy or prevent the proxy's outbound sockets from reaching the Internet.

## Project layout

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/pocketvpnbridge/MainActivity.java
app/src/main/java/com/pocketvpnbridge/ProxyService.java
app/src/main/java/com/pocketvpnbridge/Socks5Server.java
app/src/main/java/com/pocketvpnbridge/LanAccessPolicy.java
app/src/main/java/com/pocketvpnbridge/LanAddress.java
app/src/main/res/                 # app UI, colors, and icon
tools/test_core.sh                # JVM smoke test
.github/workflows/build-apk.yml   # CI APK build
```
