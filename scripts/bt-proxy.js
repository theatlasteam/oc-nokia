// Nokia 6300 Bluetooth proxy: RFCOMM SPP <-> TCP bridge port.
//
// The BT stack (bluetooth-serial-port, native) only loads under plain
// Node, while the bridge runs under bun/OpenCode. This tiny proxy joins
// them: phone --[BT SPP, UUID 0x1101]--> here --[TCP]--> bridge.
//
// Usage (from opencode-nokia-plugin/, so node_modules resolves):
//   node /home/deda/NokiaCode/scripts/bt-proxy.js
// Env: NOKIA_TCP_HOST (default 127.0.0.1), NOKIA_TCP_PORT (default 19741),
//      NOKIA_BT_CHANNEL (default 1), NOKIA_BT_UUID (default 1101 = SPP).
/* eslint-disable no-console */
"use strict";

const net = require("net");
const { BluetoothSerialPortServer } = require("bluetooth-serial-port");

const TCP_HOST = process.env.NOKIA_TCP_HOST || "127.0.0.1";
const TCP_PORT = parseInt(process.env.NOKIA_TCP_PORT || "19741", 10);
const CHANNEL = parseInt(process.env.NOKIA_BT_CHANNEL || "1", 10);
const UUID = process.env.NOKIA_BT_UUID || "1101";

const server = new BluetoothSerialPortServer();
let tcp = null;
let btAlive = false;
let tcpTimer = null;

function dropTcp() {
  try {
    if (tcp) tcp.destroy();
  } catch (_) {}
  tcp = null;
}

// Phone -> PC.
server.on("data", (buffer) => {
  if (tcp && !tcp.destroyed) {
    tcp.write(buffer);
  }
});

server.on("closed", () => {
  console.log("[bt-proxy] BT client gone, waiting for next...");
  btAlive = false;
  if (tcpTimer) {
    clearTimeout(tcpTimer);
    tcpTimer = null;
  }
  dropTcp();
});

function connectTcp(addr) {
  dropTcp();
  const sock = net.connect(TCP_PORT, TCP_HOST);
  tcp = sock;
  sock.on("connect", () => console.log("[bt-proxy] tcp up -> " + TCP_HOST + ":" + TCP_PORT));
  // PC -> phone. server.write takes a Buffer.
  sock.on("data", (chunk) => {
    try {
      server.write(Buffer.from(chunk), (err) => {
        if (err) console.log("[bt-proxy] bt write error: " + (err.message || err));
      });
    } catch (e) {
      console.log("[bt-proxy] bt write threw: " + (e.message || e));
    }
  });
  const schedule = () => {
    if (!btAlive) return;
    if (tcpTimer) return;
    tcpTimer = setTimeout(() => {
      tcpTimer = null;
      if (btAlive) {
        console.log("[bt-proxy] tcp retry...");
        connectTcp(addr);
      }
    }, 2000);
  };
  sock.on("close", () => {
    console.log("[bt-proxy] tcp closed, retrying...");
    if (tcp === sock) tcp = null;
    schedule();
  });
  sock.on("error", (e) => console.log("[bt-proxy] tcp error: " + (e.message || e)));
}

server.listen(
  (addr) => {
    console.log("[bt-proxy] BT client connected: " + addr);
    btAlive = true;
    connectTcp(addr);
  },
  (err) => {
    console.error("[bt-proxy] BT listen failed: " + ((err && err.message) || err));
    process.exit(1);
  },
  { uuid: UUID, channel: CHANNEL },
);

console.log("[bt-proxy] advertising SPP UUID 0x" + UUID + " on RFCOMM channel " + CHANNEL);
