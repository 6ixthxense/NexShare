#!/usr/bin/env bash
# Runs NexShare's JVM test suite: unit tests for the HTTP server, crypto and TLS, plus
# integration tests that boot the real server stack (auth, users, file API, trash, share
# links, WebDAV) on a temp folder. Android APIs are replaced by small fakes in test/fakes,
# and org.json (stubbed in android.jar) comes from Maven Central, so only a JDK (8+) and
# curl are needed.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=app/src/com/nexshare
OUT=build/test
DEPS=build/test-deps
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$DEPS"

JSON_JAR="$DEPS/json-20240303.jar"
JSON_URL=https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
if [ ! -f "$JSON_JAR" ]; then
  curl -fsSL -o "$JSON_JAR.tmp" "$JSON_URL"
  want=$(curl -fsSL "$JSON_URL.sha1" | cut -c1-40)
  got=$(sha1sum "$JSON_JAR.tmp" | cut -c1-40)
  [ "$want" = "$got" ] || { echo "org.json checksum mismatch ($got != $want)" >&2; rm -f "$JSON_JAR.tmp"; exit 1; }
  mv "$JSON_JAR.tmp" "$JSON_JAR"
fi

CLASSES="HttpServer Crypto CertGen Storage AuditLog LiveEvents Auth Users SessionStore Settings Trash Shares Zip FileApi WebDav"
SOURCES=""
for c in $CLASSES; do SOURCES="$SOURCES $SRC/$c.java"; done

find test/fakes test/src -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn -Xlint:-options -cp "$JSON_JAR" -d "$OUT/classes" $SOURCES @"$OUT/sources.txt"
# Filenames are UTF-8 on Android; make the test JVM match (Thai names are part of the tests).
export LC_ALL=C.UTF-8 LANG=C.UTF-8
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT/classes:$JSON_JAR" com.nexshare.Tests
