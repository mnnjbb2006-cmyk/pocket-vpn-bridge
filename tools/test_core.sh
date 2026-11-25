#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 -d "$TMP" \
  "$ROOT/app/src/main/java/com/pocketvpnbridge/LanAccessPolicy.java" \
  "$ROOT/app/src/main/java/com/pocketvpnbridge/Socks5Server.java" \
  "$ROOT/tools/smoke/com/pocketvpnbridge/Socks5ServerSmokeTest.java"
java -cp "$TMP" com.pocketvpnbridge.Socks5ServerSmokeTest
