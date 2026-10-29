#!/usr/bin/env bash
# Generates (or reuses) .tools/nexshare.keystore and uploads NEXSHARE_KEYSTORE_B64 +
# NEXSHARE_KEYSTORE_PASS to GitHub Actions secrets (or prints them if gh is not configured).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS"
KS="$TOOLS/nexshare.keystore"
PASS_FILE="$TOOLS/keystore.pass"

if [ ! -f "$KS" ]; then
  head -c 32 /dev/urandom | base64 | tr -dc 'a-z0-9' | head -c 20 > "$PASS_FILE"
  PASS="$(cat "$PASS_FILE")"
  keytool -genkeypair -keystore "$KS" -alias nexshare -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname CN=NexShare
  echo "Created new release keystore at $KS"
else
  echo "Reusing existing keystore at $KS"
fi

PASS="$(tr -d '\r\n' < "$PASS_FILE")"
B64="$(base64 < "$KS" | tr -d '\r\n')"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  printf '%s' "$B64" | gh secret set NEXSHARE_KEYSTORE_B64
  printf '%s' "$PASS" | gh secret set NEXSHARE_KEYSTORE_PASS
  echo "== Uploaded NEXSHARE_KEYSTORE_B64 and NEXSHARE_KEYSTORE_PASS to GitHub repository secrets!"
  exit 0
fi

echo ""
echo "Add these two Repository Secrets in GitHub (Settings -> Secrets and variables -> Actions):"
echo "--------------------------------------------------------------------------------"
echo "NEXSHARE_KEYSTORE_PASS = $PASS"
echo "NEXSHARE_KEYSTORE_B64  = $B64"
echo "--------------------------------------------------------------------------------"

