# OpenCode × Nokia 6300 — Bluetooth Voice Remote

Control [OpenCode](https://opencode.ai) from a real Nokia 6300 (Series 40,
MIDP 2.0 / CLDC 1.1) over Bluetooth: hold a voice note on the phone →
Groq Whisper transcribes it → the prompt runs in your OpenCode session →
answers stream back live to the 240×320 screen.

```
Phone (J2ME MIDlet) --[BT SPP / TCP]--> PC bridge --> Groq Whisper --> OpenCode
       ^------------------------------------------------------------------|
                    live status, chat lines, tool activity
```

## Quick setup

### Linux (Debian/Ubuntu/Arch/Fedora/Void — auto-detected)

```sh
./setup.sh
```

### macOS

```sh
./setup.sh          # same script; detects Darwin, uses Homebrew
```

### Windows

```powershell
.\setup.ps1
```

The setup script installs **bun**, **ffmpeg**, **JDK 8**, **ProGuard**
(for CLDC preverify) and JS dependencies, then prints the next steps.
Everything installs locally — no root needed except the package manager
calls it makes on your behalf.

### What setup does *not* do

* Pair the phone (do it once: phone BT **On + Visible**, then
  `scripts/void-bt-setup.sh` on Linux, or pair from Bluetooth settings
  on macOS/Windows).
* Set `GROQ_API_KEY` (get one at console.groq.com, then
  `export GROQ_API_KEY=gsk_…`).

## Run the stack

```sh
export GROQ_API_KEY=gsk_...
./run-all.sh        # bridge (bun) + BT proxy (node) + dashboard
```

* Dashboard: http://127.0.0.1:4097/nokia
* Phone-initiated: on the 6300 open **OpenCode Remote → Connect**
  (direct-dials the PC, no inquiry needed).
* PC-initiated: on the 6300 choose **Wait for PC**, then
  `NOKIA_BT_ADDR=00:22:66:C8:4C:07 node scripts/bt-connect.js`.

No Bluetooth on the PC (or testing in an emulator)? The bridge always
listens on **TCP :19741** — point the phone at it with the `TcpHost`
JAD property, or test with MicroEmulator over `socket://localhost:19741`.

## Build the phone app

Requirements: JDK 8 (`-source 1.3` support), ProGuard ≥ 7.4 (preverify),
`jar`. All installed by `setup.sh`.

```sh
./scripts/build-jar.sh     # stubs -> javac -> preverify -> jar/jad -> OBEX push
```

Output lands in `NokiaOpenCode/bin/` (`NokiaOpenCode.jar` + `.jad`).
Prebuilt binaries are included in this repo — you can skip the build and
OBEX-push them directly:

```sh
obexftp --bluetooth <PHONE_MAC> --put NokiaOpenCode/bin/NokiaOpenCode.jad
obexftp --bluetooth <PHONE_MAC> --put NokiaOpenCode/bin/NokiaOpenCode.jar
```

On the phone: open the `.jad` → Install → allow Bluetooth + Microphone.
On S40: App. manager → OpenCode Remote → Permissions → set
Bluetooth/Multimedia to **Always allowed**.

## Layout

```
NokiaOpenCode/            Java ME MIDlet (Nokia 6300)
  src/com/nokia/opencode/   MIDlet, BTClient, AudioRecorder, Protocol, Ui
  stub-src/                 MIDP/JSR-82/MMAPI API stubs (compile classpath)
  res/                      logo PNGs
  bin/                      prebuilt preverified jar + jad
  build.xml                 WTK / Antenna build (alternative)
opencode-nokia-plugin/    PC bridge + OpenCode plugin (server + TUI + web)
  src/bridge.ts             BT SPP + TCP + Groq Whisper + OpenCode client
  src/index.ts              server plugin (tool, command, RPC, lifecycle)
  src/tui.tsx               TUI plugin (slot, route, palette, toasts)
  src/rpc.ts                shared RPC contract (id "nokia")
  web/dashboard.html        zero-dependency live dashboard
  standalone-bridge.mjs     runs the bridge standalone (no plugin loader)
scripts/
  build-jar.sh              full phone-app build + push
  run-all.sh                start bridge + proxy + dashboard
  bt-proxy.js               BT SPP -> TCP :19741 proxy
  bt-connect.js             PC-outgoing dial ("Wait for PC" mode)
  void-bt-setup.sh          BlueZ pairing/trust helper (Linux)
setup.sh                    Linux/macOS one-shot setup
setup.ps1                   Windows one-shot setup
```

## Wire protocol (RFCOMM SPP, UTF-8 lines ending in \n)

Phone → PC:

```
HELLO Nokia6300 MIDlet-1.0
VOICE:BEGIN <totalBytes>
VOICE:DATA <base64 chunk, ~512 chars = 384 bytes audio>
VOICE:END
TEXT:<typed prompt>
CANCEL
PING
BYE
```

PC → Phone:

```
HELLO OpenCode-Bridge 1.0
STATUS <short status line>
PROGRESS <stage>|<pct 0-100>|<label>
YOU <transcript line>
TEXT <answer line>
TOOL <tool activity>
DONE
ERROR <message>
PING
```

Stages: `RECEIVING TRANSCRIBING SENDING WORKING DONE ERROR`.
Audio is AMR-NB (MMAPI `capture://audio?encoding=amr`), max 30 s.

## Web + TUI

The server plugin registers RPC id `nokia`:

```sh
opencode api post /api/rpc/nokia/status --data '{}'
opencode api post /api/rpc/nokia/sendText --data '{"text":"list files"}'
```

TUI plugin adds a sidebar status line, `nokia` route, palette commands
(`nokia.status`, `nokia.send`, `nokia.cancel`) and toasts.
Dashboard (bridge-served): `http://127.0.0.1:4097/nokia`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `br-connection-unknown` on connect | Phone BT off / out of range. Turn BT On + Visible, < 5 m away, retry. |
| Phone can't find `OpenCode-Nokia` | Re-run the BT setup (discoverable times out after ~180 s). |
| `GROQ_API_KEY` errors | `export GROQ_API_KEY=gsk_…` before starting. |
| AMR rejected by Groq | Install `ffmpeg` — the bridge auto-converts AMR→WAV. |
| `bluetooth-serial-port` build fails | Normal on some platforms. Bridge falls back to TCP `:19741`. Install build tools (`bluez`+headers on Linux, Xcode CLT on macOS, VS Build Tools on Windows) and retry `npm install bluetooth-serial-port`. |
| MIDlet `SecurityException` on BT/mic | Answer "Always allow" at install; on S40 set Permissions → Bluetooth/Multimedia → Always allowed. |

## License

MIT — see LICENSE.
