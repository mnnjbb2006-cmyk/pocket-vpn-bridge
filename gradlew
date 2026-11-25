#!/usr/bin/env sh
# Small Gradle bootstrapper. It downloads the pinned official Gradle distribution
# on first use; an installed Gradle 9.6.0 is reused from the user Gradle cache.
set -eu
VERSION="9.6.0"
GH="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_ROOT="$GH/wrapper/dists/gradle-$VERSION-bin/pocketvpnbridge"
GRADLE_HOME="$DIST_ROOT/gradle-$VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    command -v curl >/dev/null 2>&1 || { echo "curl is required to download Gradle $VERSION" >&2; exit 1; }
    command -v unzip >/dev/null 2>&1 || { echo "unzip is required to extract Gradle $VERSION" >&2; exit 1; }
    TMP="${TMPDIR:-/tmp}/pocketvpn-gradle-$VERSION-$$"
    mkdir -p "$TMP" "$DIST_ROOT"
    trap 'rm -rf "$TMP"' EXIT HUP INT TERM
    echo "Downloading Gradle $VERSION from services.gradle.org..."
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$TMP/gradle.zip"
    unzip -q "$TMP/gradle.zip" -d "$TMP"
    rm -rf "$GRADLE_HOME"
    mv "$TMP/gradle-$VERSION" "$GRADLE_HOME"
fi
exec "$GRADLE_BIN" "$@"
