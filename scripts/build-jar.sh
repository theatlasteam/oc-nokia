#!/bin/sh
# Reproducible local build of the Nokia 6300 MIDlet on Void Linux.
# Uses OpenJDK 8 (supports -source/-target 1.3 for CLDC) + ProGuard 7.4.2
# for the CLDC preverify step (Sun WTK alternative).
#
# One-time setup:
#   sudo xbps-install -Sy openjdk apache-ant
#   curl -sL -o /tmp/opencode/proguard.zip \
#     https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
#   unzip -o /tmp/opencode/proguard.zip -d /tmp/opencode
#
# Run: ./scripts/build-jar.sh
set -eu
export JAVA_HOME=/usr/lib/jvm/openjdk8
export PATH="$JAVA_HOME/bin:$PATH"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
APP="$HERE/j2me/NokiaOpenCode"
B=/tmp/opencode/j2me-build
PG_JAR=/tmp/opencode/proguard-7.4.2/lib/proguard.jar
if [ ! -f "$PG_JAR" ]; then
  echo "fetching ProGuard (one-time)..."
  mkdir -p /tmp/opencode && cd /tmp/opencode
  curl -sL -o proguard.zip https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
  unzip -q -o proguard.zip && cd "$HERE"
fi
if [ ! -f "$PG_JAR" ]; then
  echo "== fetching ProGuard (one-time) =="
  mkdir -p /tmp/opencode
  curl -sL -o /tmp/opencode/proguard.zip https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
  unzip -q -o /tmp/opencode/proguard.zip -d /tmp/opencode
fi

rm -rf "$B"
mkdir -p "$B/stub-classes" "$B/classes" "$B/verified" "$APP/bin"

echo "== stubs (MIDP/JSR-82/MMAPI API surface, compile classpath only) =="
cd "$APP/stub-src" && javac -source 1.3 -target 1.3 -nowarn \
  -d "$B/stub-classes" $(find . -name "*.java")
cd "$B/stub-classes" && jar cf "$B/stubs.jar" .

echo "== app =="
cd "$APP/src" && javac -source 1.3 -target 1.3 -nowarn \
  -cp "$B/stubs.jar" -d "$B/classes" $(find . -name "*.java")

echo "== preverify (ProGuard -microedition) =="
cat > "$B/proguard.conf" <<EOF
-injars $B/classes
-outjars $B/verified
-libraryjars $JAVA_HOME/jre/lib/rt.jar
-libraryjars $B/stubs.jar
-microedition
-dontshrink
-dontoptimize
-dontobfuscate
-dontwarn
-dontnote
-keep class com.nokia.opencode.** { *; }
EOF
java -jar "$PG_JAR" @"$B/proguard.conf"

echo "== jar + jad =="
cd "$B/verified" && jar cfm "$APP/bin/NokiaOpenCode.jar" \
  "$APP/META-INF/MANIFEST.MF" com/nokia/opencode/*.class
cd "$APP/res" && jar uf "$APP/bin/NokiaOpenCode.jar" logo.png logo_small.png
SIZE=$(stat -c%s "$APP/bin/NokiaOpenCode.jar")
sed "s/^MIDlet-Jar-Size: .*/MIDlet-Jar-Size: $SIZE/" "$APP/NokiaOpenCode.jad" \
  > "$APP/bin/NokiaOpenCode.jad"
echo "built $APP/bin/NokiaOpenCode.jar ($SIZE bytes)"

echo "== send to 6300 (phone BT must be ON) =="
PHONE=00:22:66:C8:4C:07
cd "$APP/bin" && obexftp --bluetooth "$PHONE" --put NokiaOpenCode.jad \
  && obexftp --bluetooth "$PHONE" --put NokiaOpenCode.jar
echo "sent. On the phone open the .jad to install."
