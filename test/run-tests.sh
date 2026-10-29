#!/usr/bin/env bash
# Runs NexShare's JVM test suite: unit tests for the HTTP server, crypto and TLS, plus
# integration tests that boot the real server stack (auth, users, file API, trash, share
# links, WebDAV) on a temp folder. Android APIs are replaced by small fakes in test/fakes,
# so only a JDK (8+) is needed.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=app/src/com/nexshare
OUT=build/test
rm -rf "$OUT" && mkdir -p "$OUT/classes"

CLASSES="HttpServer Crypto CertGen Storage AuditLog Auth Users SessionStore Settings Trash Shares Zip FileApi WebDav"
SOURCES=""
for c in $CLASSES; do SOURCES="$SOURCES $SRC/$c.java"; done

find test/fakes test/lib test/src -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn -Xlint:-options -d "$OUT/classes" $SOURCES @"$OUT/sources.txt"
# Filenames are UTF-8 on Android; make the test JVM match (Thai names are part of the tests).
export LC_ALL=C.UTF-8 LANG=C.UTF-8
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT/classes" com.nexshare.Tests
