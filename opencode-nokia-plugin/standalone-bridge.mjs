// Standalone Nokia bridge runner (no plugin system needed).
//
// Uses the OpenCode HTTP API via @opencode/client: auto-discovers the
// background service, creates/prompts sessions, streams events.
// The phone link (TCP/BT), Groq Whisper, and dashboard are identical
// to the plugin path — same bridge.ts.
//
// Run (from this directory so node_modules resolves):
//   GROQ_API_KEY=... bun ./standalone-bridge.mjs
// Env: NOKIA_DIR (session directory, default /home/deda/NokiaCode),
//      NOKIA_TCP_PORT (19741), NOKIA_DASHBOARD_PORT (4097),
//      NOKIA_WHISPER_MODEL (whisper-large-v3-turbo).
import { OpenCode } from "@opencode/client"
import { Service } from "@opencode/client/service"
import { createBridge } from "./src/bridge.ts"

const directory = process.env.NOKIA_DIR ?? "/home/deda/NokiaCode"

const endpoint = await Service.ensure()
const client = OpenCode.make({
  baseUrl: endpoint.url,
  headers: Service.headers(endpoint),
})
console.log("[standalone] opencode server:", endpoint.url, "| dir:", directory)

const ctx = {
  session: {
    create: (input) => client.session.create({ ...input, location: { directory } }),
    prompt: (input) => client.session.prompt(input),
    interrupt: (input) => client.session.interrupt(input),
    switchModel: (input) => client.session.switchModel(input),
  },
  event: {
    subscribe: (options) => client.event.subscribe(options),
  },
}

const bridge = createBridge(
  ctx,
  () => {},
  {
    tcpPort: parseInt(process.env.NOKIA_TCP_PORT ?? "19741", 10),
    dashboardPort: parseInt(process.env.NOKIA_DASHBOARD_PORT ?? "4097", 10),
    whisperModel: process.env.NOKIA_WHISPER_MODEL ?? "whisper-large-v3-turbo",
  },
)

process.on("SIGINT", () => {
  bridge.stop()
  process.exit(0)
})
process.on("SIGTERM", () => {
  bridge.stop()
  process.exit(0)
})
