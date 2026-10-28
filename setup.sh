#!/usr/bin/env bash
# Linux/macOS counterpart of setup.ps1: fetches the Android SDK pieces, libtorrent4j,
# cloudflared and busybox into .tools/ and libs/. Needs a JDK 17+ on PATH (or JAVA_HOME).
#
#   ./setup.sh && ./build.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/.tools"
DL="$TOOLS/downloads"
SDK="${ANDROID_SDK_ROOT:-$TOOLS/sdk}"
LIBS="$ROOT/libs"
mkdir -p "$TOOLS" "$DL" "$LIBS"

fetch() { # url dest
  [ -s "$2" ] && return 0
  echo "== $(basename "$2")"
  curl -fL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

command -v java >/dev/null || { echo "Install a JDK 17+ first (e.g. Temurin)"; exit 1; }

# Android command-line tools (skipped when an SDK with sdkmanager already exists, e.g. on CI)
SDKMANAGER="$(command -v sdkmanager || true)"
if [ -z "$SDKMANAGER" ] && [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"; fi
if [ -z "$SDKMANAGER" ]; then
  case "$(uname -s)" in
    Darwin) os=mac ;;
    *) os=linux ;;
  esac
  fetch "https://dl.google.com/android/repository/commandlinetools-${os}-11076708_latest.zip" "$DL/cmdline.zip"
  rm -rf "$DL/cmdline" && mkdir -p "$DL/cmdline" "$SDK/cmdline-tools"
  unzip -q "$DL/cmdline.zip" -d "$DL/cmdline"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$DL/cmdline/cmdline-tools" "$SDK/cmdline-tools/latest"
  SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
fi

echo "== platform 34 + build-tools 36 (accepting SDK licenses)"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
# build-tools 36: 34's d8 crashes on libtorrent4j.
"$SDKMANAGER" --sdk_root="$SDK" "platforms;android-34" "build-tools;36.0.0" >/dev/null

LT_VER=2.1.0-39
for art in libtorrent4j libtorrent4j-android-arm64 libtorrent4j-android-arm; do
  fetch "https://repo1.maven.org/maven2/org/libtorrent4j/$art/$LT_VER/$art-$LT_VER.jar" "$LIBS/$art-$LT_VER.jar"
done
for cf in arm64 arm; do
  fetch "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$cf" "$LIBS/cloudflared-linux-$cf"
done
for bb in busybox-arm64 busybox-arm; do
  fetch "https://github.com/Magisk-Modules-Repo/busybox-ndk/raw/master/$bb" "$LIBS/$bb"
done

echo "$SDK" > "$TOOLS/sdk.path"
echo "== setup done — now run: ./build.sh"
