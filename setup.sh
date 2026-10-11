#!/bin/sh
# OpenCode x Nokia 6300 — one-shot setup for Linux and macOS.
# Installs: bun, ffmpeg, JDK 8, ProGuard (CLDC preverify), JS deps.
# Idempotent: safe to re-run.
set -eu

say() { printf '\033[1;36m==\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$1" >&2; }

OS="$(uname -s)"
ARCH="$(uname -m)"
HERE="$(cd "$(dirname "$0")" && pwd)"

have() { command -v "$1" >/dev/null 2>&1; }

# ---------- 1. bun ----------
if have bun; then
  say "bun: $(bun --version)"
else
  say "installing bun"
  curl -fsSL https://bun.sh/install | bash
  export PATH="$HOME/.bun/bin:$PATH"
  have bun || { echo "bun install failed; add ~/.bun/bin to PATH and re-run" >&2; exit 1; }
fi

# ---------- 2. ffmpeg ----------
if have ffmpeg; then
  say "ffmpeg: ok"
elif [ "$OS" = "Darwin" ] && have brew; then
  say "installing ffmpeg (brew)"
  brew install ffmpeg
elif have apt-get; then
  say "installing ffmpeg (apt, may ask for sudo)"
  sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg
elif have pacman; then
  say "installing ffmpeg (pacman, may ask for sudo)"
  sudo pacman -Sy --noconfirm ffmpeg
elif have dnf; then
  say "installing ffmpeg (dnf, may ask for sudo)"
  sudo dnf install -y ffmpeg
elif have xbps-install; then
  say "installing ffmpeg (xbps, may ask for sudo)"
  sudo xbps-install -Sy ffmpeg
else
  warn "ffmpeg not found and no known package manager; install it manually"
fi

# ---------- 3. JDK 8 + ProGuard (phone-app build only) ----------
find_jdk8() {
  for c in "$JAVA_HOME" /usr/lib/jvm/java-8-openjdk* /usr/lib/jvm/java-1.8* \
           /usr/lib/jvm/zulu8* /Library/Java/JavaVirtualMachines/*1.8*/Contents/Home \
           "$HOME/.sdkman/candidates/java/8"*; do
    [ -x "$c/bin/javac" ] && { echo "$c"; return 0; }
  done
  return 1
}

JDK8="$(find_jdk8 || true)"
if [ -n "$JDK8" ]; then
  say "JDK 8: $JDK8"
else
  warn "JDK 8 not found — needed only to BUILD the MIDlet (prebuilt jar included)."
  if [ "$OS" = "Darwin" ] && have brew; then
    say "installing temurin@8 (brew)"
    brew install --cask temurin@8 || warn "could not install JDK 8 automatically"
  elif have apt-get; then
    say "installing openjdk-8 (apt, may ask for sudo)"
    sudo apt-get install -y -qq openjdk-8-jdk || warn "could not install JDK 8 automatically"
  fi
fi

if [ -n "$JDK8" ]; then
  PG_DIR="$HOME/.cache/oc-nokia/proguard-7.4.2"
  if [ -f "$PG_DIR/lib/proguard.jar" ]; then
    say "ProGuard: $PG_DIR"
  else
    say "downloading ProGuard 7.4.2 (preverify)"
    mkdir -p "$HOME/.cache/oc-nokia"
    curl -fsSL -o /tmp/oc-nokia-proguard.zip \
      https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
    unzip -q -o /tmp/oc-nokia-proguard.zip -d "$HOME/.cache/oc-nokia"
    rm -f /tmp/oc-nokia-proguard.zip
  fi
fi

# ---------- 4. JS deps ----------
say "installing plugin JS deps (bun)"
cd "$HERE/opencode-nokia-plugin"
bun install

# bluetooth-serial-port is a native module; failure is non-fatal
# (the bridge falls back to TCP :19741).
if ! npm install bluetooth-serial-port --no-save; then
  warn "bluetooth-serial-port native build failed — bridge will use TCP :19741 fallback"
fi

# ---------- 5. hints ----------
say "setup complete"
cat <<'EOF'
Next steps:
  1. export GROQ_API_KEY=gsk_...        (console.groq.com)
  2. ./run-all.sh                       (bridge + BT proxy + dashboard)
  3. On the 6300: install NokiaOpenCode/bin/NokiaOpenCode.jad,
     then OpenCode Remote -> Connect.
Linux pairing helper: ./scripts/void-bt-setup.sh
Phone app rebuild:    ./scripts/build-jar.sh   (needs JDK 8, installed above)
EOF
