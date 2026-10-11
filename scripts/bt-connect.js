// PC connects OUT to the Nokia 6300 ("pair PC to Nokia").
//
// The phone runs OpenCode Remote -> "Wait for PC" (it advertises SPP,
// UUID 0x1101). This script finds the phone's SPP channel over SDP,
// connects, and pipes bytes to/from the bridge TCP port.
//
// Usage (from opencode-nokia-plugin/, so node_modules resolves):
//   NOKIA_BT_ADDR=00:22:66:C8:4C:07 node /home/deda/NokiaCode/scripts/bt-connect.js
// Env: NOKIA_BT_ADDR (default 00:22:66:C8:4C:07),
//      NOKIA_TCP_HOST (127.0.0.1), NOKIA_TCP_PORT (19741).
/* eslint-disable no-console */
"use strict";

const net = require("net");
const { BluetoothSerialPort } = require("bluetooth-serial-port");

const ADDR = process.env.NOKIA_BT_ADDR || "00:22:66:C8:4C:07";
const TCP_HOST = process.env.NOKIA_TCP_HOST || "127.0.0.1";
const TCP_PORT = parseInt(process.env.NOKIA_TCP_PORT || "19741", 10);

const bt = new BluetoothSerialPort();
let tcp = null;

bt.on("data", (buffer) => {
  if (tcp && !tcp.destroyed) tcp.write(buffer);
});

bt.on("closed", () => {
  console.log("[bt-connect] BT closed, exiting");
  process.exit(0);
});

bt.on("failure", (err) => {
  console.log("[bt-connect] BT failure: " + (err && err.message ? err.message : err));
});

function pipeTcp() {
  tcp = net.connect(TCP_PORT, TCP_HOST);
  tcp.on("connect", () => console.log("[bt-connect] tcp up -> " + TCP_HOST + ":" + TCP_PORT));
  tcp.on("data", (chunk) => bt.write(Buffer.from(chunk), () => {}));
  tcp.on("close", () => console.log("[bt-connect] tcp closed"));
  tcp.on("error", (e) => console.log("[bt-connect] tcp error: " + (e.message || e)));
}

console.log("[bt-connect] SDP lookup on " + ADDR + " ...");
bt.findSerialPortChannel(
  ADDR,
  (channel) => {
    console.log("[bt-connect] phone SPP channel: " + channel + ", connecting...");
    bt.connect(
      ADDR,
      channel,
      () => {
        console.log("[bt-connect] RFCOMM connected!");
        pipeTcp();
      },
      (err) => {
        console.log("[bt-connect] connect failed: " + (err && err.message ? err.message : err));
        process.exit(1);
      },
    );
  },
  () => {
    console.log("[bt-connect] no SPP service found on " + ADDR + " (is the phone on 'Wait for PC'?)");
    process.exit(1);
  },
);
