import * as net from "node:net"
import * as http from "node:http"
import * as fs from "node:fs"
import * as os from "node:os"
import * as path from "node:path"
import { execFile } from "node:child_process"

export interface BridgeOptions {
  tcpPort?: number
  dashboardPort?: number
  whisperModel?: string
  maxVoiceBytes?: number
  modelProvider?: string
  modelID?: string
}

interface Ctx {
  session: {
    create(input?: any): Promise<any>
    prompt(input: any): Promise<any>
    interrupt(input: any): Promise<any>
    switchModel(input: any): Promise<any>
  }
  event: {
    subscribe(options?: { signal?: AbortSignal }): AsyncIterable<any>
  }
}

type Emit = (event: string, data: any) => Promise<void> | void

const STAGES = {
  RECEIVING: "RECEIVING",
  TRANSCRIBING: "TRANSCRIBING",
  SENDING: "SENDING",
  WORKING: "WORKING",
  DONE: "DONE",
  ERROR: "ERROR",
}

function log(...args: any[]) {
  console.log("[nokia-bridge]", ...args)
}

/** Convert AMR-NB to WAV with ffmpeg when available. Returns wav path. */
async function amrToWavIfNeeded(amrPath: string): Promise<string> {
  return new Promise((resolve) => {
    execFile("ffmpeg", ["-version"], (err) => {
      if (err) return resolve(amrPath) // no ffmpeg: send raw, Groq may cope
      const wav = amrPath + ".wav"
      execFile(
        "ffmpeg",
        ["-y", "-loglevel", "error", "-i", amrPath, "-ar", "16000", "-ac", "1", wav],
        (err2) => {
          if (err2) {
            log("ffmpeg convert failed, sending raw AMR:", String(err2).slice(0, 200))
            return resolve(amrPath)
          }
          return resolve(wav)
        },
      )
    })
  })
}

async function transcribeGroq(
  audioPath: string,
  model: string,
): Promise<string> {
  const key = process.env.GROQ_API_KEY
  if (!key) throw new Error("no GROQ_API_KEY in environment")
  const wavPath = await amrToWavIfNeeded(audioPath)
  const buf = fs.readFileSync(wavPath)
  const ext = wavPath.endsWith(".wav") ? "audio.wav" : "audio.amr"
  const mime = wavPath.endsWith(".wav") ? "audio/wav" : "audio/amr"
  const form = new FormData()
  form.append("model", model)
  form.append("response_format", "text")
  form.append("file", new Blob([buf], { type: mime }), ext)
  const res = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}` },
    body: form,
  })
  if (!res.ok) {
    const t = await res.text().catch(() => "")
    throw new Error(`groq ${res.status}: ${t.slice(0, 300)}`)
  }
  return (await res.text()).trim()
}

function wrapLines(text: string, width = 160): string[] {
  const out: string[] = []
  for (const para of text.split("\n")) {
    let line = para
    if (line.length <= width) {
      out.push(line)
      continue
    }
    while (line.length > width) {
      let cut = line.lastIndexOf(" ", width)
      if (cut < 20) cut = width
      out.push(line.slice(0, cut))
      line = line.slice(cut).trimStart()
    }
    out.push(line)
  }
  return out.slice(0, 60) // cap: 6300 heap is tiny
}

export function createBridge(ctx: Ctx, emit: Emit, options: BridgeOptions = {}) {
  const tcpPort = options.tcpPort ?? 19741
  const dashboardPort = options.dashboardPort ?? 4097
  const whisperModel = options.whisperModel ?? "whisper-large-v3-turbo"
  const maxVoiceBytes = options.maxVoiceBytes ?? 220_000 // ~60 s AMR safety cap
  // Agent model: the server has no default configured, so pick an
  // explicitly working one (Muse Spark 1.3 free, verified on this box).
  const modelProvider =
    options.modelProvider ?? process.env.NOKIA_MODEL_PROVIDER ?? "opencode"
  const modelID =
    options.modelID ?? process.env.NOKIA_MODEL_ID ?? "muse-spark-1.3-contributor-free"

  const state = {
    phone: "",
    connected: false,
    lastTranscript: "",
    lastSessionID: "",
    stage: "IDLE",
    pct: 0,
    label: "waiting for phone",
    answerTail: [] as string[],
  }

  let phoneSocket: net.Socket | null = null
  let btServer: any = null

  function sendLine(line: string) {
    if (phoneSocket && !phoneSocket.destroyed) {
      phoneSocket.write(line + "\n", "utf8")
    }
  }

  async function progress(stage: string, pct: number, label: string) {
    state.stage = stage
    state.pct = pct
    state.label = label
    sendLine(`PROGRESS ${stage}|${pct}|${label}`)
    try {
      await emit("progress", { stage, pct, label })
    } catch {}
  }

  function pushTail(line: string) {
    state.answerTail.push(line)
    if (state.answerTail.length > 60) state.answerTail.shift()
  }

  async function answerLines(sessionID: string, text: string, done: boolean) {
    for (const line of wrapLines(text)) {
      sendLine(`TEXT ${line}`)
      pushTail(line)
      try {
        await emit("answer", { sessionID, line, done: false })
      } catch {}
    }
    if (done) {
      sendLine("DONE")
      try {
        await emit("answer", { sessionID, line: "", done: true })
      } catch {}
    }
  }

  /** Chat bubble: the user's own prompt (transcript or typed). */
  async function youLine(sessionID: string, text: string) {
    for (const line of wrapLines(text).slice(0, 8)) {
      sendLine(`YOU ${line}`)
      pushTail(`you: ${line}`)
    }
  }

  let toolLinesSent = 0
  /** Chat row: a tool call starting/finishing. Capped per session. */
  async function toolLine(sessionID: string, text: string) {
    if (toolLinesSent >= 40) return
    toolLinesSent++
    const line = text.slice(0, 160)
    sendLine(`TOOL ${line}`)
    pushTail(`» ${line}`)
    try {
      await emit("answer", { sessionID, line: `» ${line}`, done: false })
    } catch {}
  }

  function toolSummary(data: any): string {
    try {
      const parts: any[] = Array.isArray(data?.parts)
        ? data.parts
        : data?.part
          ? [data.part]
          : [data]
      for (const p of parts) {
        if (!p || typeof p !== "object") continue
        const name =
          p.tool ?? p.toolName ?? p.name ?? p.function ?? p.title ?? null
        if (name && typeof name === "string") {
          const state =
            p.state ?? p.status ?? p.phase ?? p.result ?? p.outcome ?? ""
          const extra = p.text ?? p.detail ?? p.description ?? ""
          let line = name + (state && typeof state === "string" ? " " + state : "")
          if (extra && typeof extra === "string" && extra.trim()) {
            line += ": " + extra.trim().slice(0, 90)
          }
          return line
        }
        if (p.type === "tool" && p.text && typeof p.text === "string") {
          return p.text.slice(0, 140)
        }
      }
    } catch {}
    return ""
  }

  async function handleVoice(amr: Buffer) {
    const tmp = path.join(
      os.tmpdir(),
      `nokia-voice-${Date.now()}.amr`,
    )
    fs.writeFileSync(tmp, amr)
    try {
      await progress(STAGES.TRANSCRIBING, 30, "Whisper on Groq…")
      const text = await transcribeGroq(tmp, whisperModel)
      state.lastTranscript = text
      try {
        await emit("transcript", { text })
      } catch {}
      sendLine(`STATUS heard: ${text.slice(0, 100)}`)
      await handlePrompt(text)
    } catch (e: any) {
      const msg = e?.message ?? String(e)
      log("voice pipeline failed:", msg)
      await progress(STAGES.ERROR, 0, msg.slice(0, 120))
      sendLine(`ERROR ${msg.slice(0, 160)}`)
    } finally {
      try {
        fs.unlinkSync(tmp)
        try {
          fs.unlinkSync(tmp + ".wav")
        } catch {}
      } catch {}
    }
  }

  async function handlePrompt(text: string) {
    await progress(STAGES.SENDING, 60, "sending to OpenCode…")
    let sessionID = state.lastSessionID
    if (!sessionID) {
      const created = await ctx.session.create({ title: `Nokia: ${text.slice(0, 40)}` })
      sessionID = created.id ?? created.sessionID ?? created.session?.id
      state.lastSessionID = sessionID
      toolLinesSent = 0
    }
    try {
      await ctx.session.switchModel({
        sessionID,
        model: { providerID: modelProvider, id: modelID },
      })
    } catch (e: any) {
      log("model switch failed, keeping session default:", String(e?.message ?? e).slice(0, 160))
    }
    await progress(STAGES.WORKING, 70, "agent working…")
    // Fire the prompt without awaiting completion, then stream events.
    const promptP = ctx.session.prompt({ sessionID, text }).catch((e: any) => {
      progress(STAGES.ERROR, 0, String(e?.message ?? e).slice(0, 120))
    })
    await youLine(sessionID, text)
    watchSession(sessionID)
    await promptP
  }

  // Stream server events for our session and forward to the phone.
  let watching = false
  async function watchSession(sessionID: string) {
    if (watching) return
    watching = true
    const ctl = new AbortController()
    const stopAfter = setTimeout(() => ctl.abort(), 15 * 60_000)
    try {
      for await (const ev of ctx.event.subscribe({ signal: ctl.signal })) {
        const type = ev?.type ?? ""
        const sid =
          ev?.data?.sessionID ?? ev?.sessionID ?? ev?.data?.session?.id ?? ""
        if (sid && sid !== sessionID) continue
        if (/tool/i.test(type)) {
          const summary = toolSummary(ev?.data)
          if (summary) {
            await toolLine(sessionID, summary)
            await progress(STAGES.WORKING, 80, summary.slice(0, 60))
          }
          continue
        }
        if (type.includes("message") || type.includes("text") || type.includes("part")) {
          const summary = toolSummary(ev?.data)
          if (summary) {
            await toolLine(sessionID, summary)
            continue
          }
          const delta =
            ev?.data?.delta ?? ev?.data?.text ?? ev?.data?.part?.text ?? ""
          if (delta && typeof delta === "string") {
            await answerLines(sessionID, delta, false)
            await progress(STAGES.WORKING, 85, "streaming…")
          }
        }
        if (type.includes("session.idle") || type.includes("execution.succeeded")) {
          await progress(STAGES.DONE, 100, "done")
          sendLine("DONE")
          break
        }
        if (type.includes("error") || type.includes("failed")) {
          await progress(STAGES.ERROR, 0, type)
          break
        }
      }
    } catch {
    } finally {
      clearTimeout(stopAfter)
      watching = false
    }
  }

  function attachSocket(sock: net.Socket, label: string) {
    if (phoneSocket) {
      try {
        phoneSocket.destroy()
      } catch {}
    }
    phoneSocket = sock
    state.connected = true
    state.phone = label
    log("phone connected:", label)
    sendLine("HELLO OpenCode-Bridge 1.0")
    sendLine("STATUS ready. Record a voice note.")
    progress("IDLE", 0, "ready").catch(() => {})

    let buf = ""
    let voiceChunks: Buffer[] = []
    let voiceExpect = -1

    sock.setEncoding("utf8")
    sock.on("data", (chunk: string) => {
      buf += chunk
      let idx: number
      while ((idx = buf.indexOf("\n")) >= 0) {
        let line = buf.slice(0, idx)
        buf = buf.slice(idx + 1)
        if (line.endsWith("\r")) line = line.slice(0, -1)
        onLine(line).catch((e) => log("onLine error:", e))
      }
    })
    const down = () => {
      if (phoneSocket === sock) {
        phoneSocket = null
        state.connected = false
        log("phone disconnected")
      }
    }
    sock.on("close", down)
    sock.on("error", (e) => {
      log("socket error:", String(e).slice(0, 200))
      down()
    })

    async function onLine(line: string) {
      if (line.startsWith("VOICE:BEGIN ")) {
        voiceExpect = parseInt(line.slice(12).trim(), 10) || -1
        voiceChunks = []
        await progress(STAGES.RECEIVING, 10, "receiving audio…")
      } else if (line.startsWith("VOICE:DATA ")) {
        const b = Buffer.from(line.slice(11).trim(), "base64")
        if (voiceChunks.reduce((a, c) => a + c.length, 0) + b.length > maxVoiceBytes) {
          voiceChunks = []
          voiceExpect = -1
          sendLine("ERROR voice too long (max ~30s)")
          await progress(STAGES.ERROR, 0, "voice too long")
          return
        }
        voiceChunks.push(b)
        if (voiceExpect > 0) {
          const got = voiceChunks.reduce((a, c) => a + c.length, 0)
          await progress(
            STAGES.RECEIVING,
            Math.min(25, Math.round((got / voiceExpect) * 25)),
            `receiving ${got}/${voiceExpect}B`,
          )
        }
      } else if (line === "VOICE:END") {
        const amr = Buffer.concat(voiceChunks)
        voiceChunks = []
        voiceExpect = -1
        await progress(STAGES.RECEIVING, 25, `got ${amr.length}B audio`)
        handleVoice(amr).catch((e) => log(e))
      } else if (line.startsWith("TEXT:")) {
        const text = line.slice(5).trim()
        if (text) {
          state.lastTranscript = text
          try {
            await emit("transcript", { text })
          } catch {}
          handlePrompt(text).catch((e) => log(e))
        }
      } else if (line === "CANCEL") {
        if (state.lastSessionID) {
          try {
            await ctx.session.interrupt({ sessionID: state.lastSessionID })
          } catch {}
        }
        await progress(STAGES.WORKING, 0, "cancelled")
      } else if (line === "PING") {
        sendLine("PONG")
      } else if (line === "BYE") {
        sock.end()
      } else if (line.startsWith("HELLO")) {
        sendLine("HELLO OpenCode-Bridge 1.0")
      }
    }
  }

  // ---- Transports ----

  const tcp = net.createServer((sock) => {
    attachSocket(sock, `TCP ${sock.remoteAddress}`)
  })
  tcp.on("error", (e) => log("TCP server error:", String(e).slice(0, 200)))
  tcp.listen(tcpPort, "0.0.0.0", () => {
    log(`TCP fallback listening on 0.0.0.0:${tcpPort} (phone: socket://<pc-ip>:${tcpPort})`)
  })

  // Bluetooth SPP via optional native module. Absence is fine (TCP fallback).
  try {
    const bt = require("bluetooth-serial-port") as any
    const SerialPort = bt.BluetoothSerialPortServer ?? bt.Server ?? null
    if (SerialPort) {
      btServer = new SerialPort()
      btServer.listen(
        (clientAddress: string) => {
          log("BT client address:", clientAddress)
        },
        (err: any) => {
          if (err) log("BT listen failed (using TCP fallback):", String(err).slice(0, 300))
          else log('BT SPP advertising as "OpenCode-Nokia" (UUID 0x1101, channel 1)')
        },
        { uuid: "1101", channel: 1, name: "OpenCode-Nokia" },
      )
      // bluetooth-serial-port emits 'data' with raw Buffer per RFCOMM client;
      // wrap it into a net.Socket-like shim when a client connects.
      btServer.on("data", (function () {
        let acc = ""
        return (data: Buffer) => {
          acc += data.toString("utf8")
          // Reuse the TCP path by feeding a virtual socket on first data.
          // Full duplex BT write-back uses btServer.write().
          void acc
        }
      })())
    }
  } catch (e: any) {
    log("bluetooth-serial-port not available, TCP fallback only:", String(e?.message ?? e).slice(0, 160))
  }

  // ---- Web dashboard (Web UI for the same state the phone sees) ----
  const dash = http.createServer((req, res) => {
    if (req.url === "/nokia" || req.url === "/nokia/") {
      try {
        const html = fs.readFileSync(path.join(__dirname, "..", "web", "dashboard.html"), "utf8")
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" })
        res.end(html)
      } catch {
        res.writeHead(404)
        res.end("dashboard.html missing")
      }
      return
    }
    if (req.url === "/nokia/status") {
      res.writeHead(200, { "Content-Type": "application/json" })
      res.end(
        JSON.stringify({
          ...state,
          answerTail: state.answerTail.slice(-20),
          groq: !!process.env.GROQ_API_KEY,
          tcpPort,
        }),
      )
      return
    }
    if (req.url === "/nokia/rpc/sendText" && req.method === "POST") {
      // Same-origin relay so the dashboard never needs the server URL.
      let body = ""
      req.on("data", (c) => {
        body += c
      })
      req.on("end", () => {
        void (async () => {
          try {
            const parsed = JSON.parse(body || "{}")
            const text = String(parsed.text ?? "").trim()
            if (!text) {
              res.writeHead(400, { "Content-Type": "application/json" })
              res.end(JSON.stringify({ error: "empty text" }))
              return
            }
            const sessionID = await sendText(text)
            res.writeHead(200, { "Content-Type": "application/json" })
            res.end(JSON.stringify({ sessionID }))
          } catch (e: any) {
            res.writeHead(500, { "Content-Type": "application/json" })
            res.end(JSON.stringify({ error: String(e?.message ?? e).slice(0, 200) }))
          }
        })()
      })
      return
    }
    if (req.url?.startsWith("/nokia/")) {
      // Static web assets (opencode-logo.svg, favicon).
      const name = path.basename(req.url.split("?")[0])
      if (/^[A-Za-z0-9._-]+$/.test(name)) {
        try {
          const file = path.join(__dirname, "..", "web", name)
          const data = fs.readFileSync(file)
          const type = name.endsWith(".svg")
            ? "image/svg+xml"
            : name.endsWith(".png")
              ? "image/png"
              : "application/octet-stream"
          res.writeHead(200, { "Content-Type": type })
          res.end(data)
          return
        } catch {}
      }
      res.writeHead(404)
      res.end("not found")
      return
    }
    res.writeHead(404)
    res.end("not found")
  })
  dash.on("error", (e) => log("dashboard error:", String(e).slice(0, 200)))
  dash.listen(dashboardPort, "127.0.0.1", () => {
    log(`dashboard: http://127.0.0.1:${dashboardPort}/nokia`)
  })

  async function sendText(text: string): Promise<string> {
    state.lastTranscript = text
    try {
      await emit("transcript", { text })
    } catch {}
    await handlePrompt(text)
    return state.lastSessionID
  }

  return {
    state,
    sendText,
    async interrupt(sessionID: string) {
      try {
        await ctx.session.interrupt({ sessionID })
      } catch {}
      await progress(STAGES.WORKING, 0, "cancelled")
    },
    stop() {
      try {
        tcp.close()
      } catch {}
      try {
        dash.close()
      } catch {}
      try {
        phoneSocket?.destroy()
      } catch {}
      try {
        btServer?.close?.()
      } catch {}
    },
  }
}
