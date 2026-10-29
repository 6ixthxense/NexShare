#!/usr/bin/env bash
# End-to-end test run inside the CI Android Emulator: installs the built APK, starts
# MainActivity + ServerService, and verifies live HTTP (:8080), plain HTTP -> HTTPS
# redirect on :8443, and HTTPS (:8443) responses.
set -euo pipefail

adb wait-for-device
APK="$(ls dist/NexShare-v*.apk | head -n 1)"
echo "== Installing $APK on emulator..."
if ! adb install -r -g "$APK"; then
  echo "== Repackaging APK without ARM-only native libs for x86_64 emulator..."
  cp "$APK" /tmp/emu-unaligned.apk
  zip -d /tmp/emu-unaligned.apk 'lib/*' || true
  BT="$(ls -d "$ANDROID_HOME/build-tools/"* | sort -V | tail -n 1)"
  "$BT/zipalign" -p -f 4 /tmp/emu-unaligned.apk /tmp/emu-aligned.apk
  rm -f /tmp/emu.keystore /tmp/emu.apk
  keytool -genkeypair -keystore /tmp/emu.keystore -alias emu -keyalg RSA -keysize 2048 -validity 10 \
    -storepass android -keypass android -dname CN=NexShare
  "$BT/apksigner" sign --ks /tmp/emu.keystore --ks-pass pass:android --out /tmp/emu.apk /tmp/emu-aligned.apk
  adb install -r -g /tmp/emu.apk
fi

adb shell appops set com.nexshare MANAGE_EXTERNAL_STORAGE allow || true
adb shell am start -n com.nexshare/.MainActivity
sleep 2
adb shell am start-foreground-service -n com.nexshare/.ServerService || adb shell am startservice -n com.nexshare/.ServerService || true

adb forward tcp:8080 tcp:8080
adb forward tcp:8443 tcp:8443

echo "== Waiting for HTTP server on :8080..."
for i in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8080/api/session > /tmp/session.json; then
    break
  fi
  sleep 1
done
cat /tmp/session.json
echo ""
grep -q '"pinRequired":true' /tmp/session.json

echo "== Waiting for HTTPS server on :8443..."
for i in $(seq 1 40); do
  if curl -skf https://127.0.0.1:8443/api/session > /tmp/https_session.json; then
    break
  fi
  sleep 1
done
cat /tmp/https_session.json
echo ""
grep -q '"secure":true' /tmp/https_session.json

echo "== Testing plain HTTP -> HTTPS port 8443 redirect..."
REDIRECT="$(curl -sI http://127.0.0.1:8443/ | tr -d '\r')"
echo "$REDIRECT"
echo "$REDIRECT" | grep -qi "301 Moved Permanently"
echo "$REDIRECT" | grep -qi "Location: https://127.0.0.1:8443/"

echo "== Android Emulator end-to-end HTTP + HTTPS checks passed!"

