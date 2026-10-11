#!/bin/sh
# Start the Nokia 6300 remote stack: BT proxy (node) + bridge (bun).
# Phone --[Bluetooth SPP]--> proxy --[TCP 127.0.0.1:19741]--> bridge
#   -> Groq Whisper -> OpenCode sessions, dashboard on :4097.
#
# Env (all optional, shown with defaults):
#   GROQ_API_KEY=...      required for transcription (export first!)
#   NOKIA_DIR=/home/deda/NokiaCode
#   NOKIA_TCP_PORT=19741 NOKIA_DASHBOARD_PORT=4097
#   NOKIA_WHISPER_MODEL=whisper-large-v3-turbo
#   NOKIA_MODEL_PROVIDER=opencode NOKIA_MODEL_ID=muse-spark-1.3-contributor-free
#     (agent model; server has no default — Spark 1.3 free verified here)
# Logs: /tmp/bridge.log (bridge) and /tmp/btproxy.log (proxy).
# Stop: kill $(cat /tmp/bridge.pid /tmp/btproxy.pid)
set -eu
PLUGIN_DIR="$(cd "$(dirname "$0")/../opencode-nokia-plugin" && pwd)"

if [ -z "${GROQ_API_KEY:-}" ]; then
  echo "error: export GROQ_API_KEY first" >&2
  exit 1
fi

cd "$PLUGIN_DIR"
echo "== bridge (bun) =="
nohup bun ./standalone-bridge.mjs > /tmp/bridge.log 2>&1 &
echo $! > /tmp/bridge.pid
sleep 3
grep -a "dashboard:" /tmp/bridge.log | tail -1

echo "== BT proxy (node) =="
export NODE_PATH="$PLUGIN_DIR/node_modules"
nohup node "$PLUGIN_DIR/../scripts/bt-proxy.js" > /tmp/btproxy.log 2>&1 &
echo $! > /tmp/btproxy.pid
sleep 3
cat /tmp/btproxy.log | head -2

echo ""
echo "Dashboard: http://127.0.0.1:4097/nokia"
echo "Phone-initiated: on the 6300 open OpenCode Remote -> Connect."
echo "PC-initiated: on the 6300 choose 'Wait for PC', then run:"
echo "  NOKIA_BT_ADDR=00:22:66:C8:4C:07 node scripts/bt-connect.js"
