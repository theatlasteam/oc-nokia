import { Plugin } from "@opencode/plugin"
import { Nokia } from "./rpc.js"
import { createBridge } from "./bridge.js"

/**
 * Server plugin: owns the Bluetooth/TCP bridge, Groq Whisper transcription,
 * OpenCode session prompting, RPC (Web), tool + command.
 * TUI lives in src/tui.tsx (exported as ./tui).
 */
export default Plugin.define({
  id: "nokia-bridge",
  async setup(ctx) {
    // NOTE: ctx.options read disabled: testing whether option access
    // breaks loading on this server version. Defaults match config.
    const opts: {
      tcpPort?: number
      dashboardPort?: number
      whisperModel?: string
      modelProvider?: string
      modelID?: string
    } = {} /* (ctx.options ?? {}) as any */

    // Bridge FIRST: RPC handlers below close over it, and the server
    // may inspect handlers during registration (TDZ-safe this way).
    const bridge = createBridge(ctx as any, (event, data) => {
      void registration.events.emit(event as any, data)
    }, {
      tcpPort: opts.tcpPort ?? 19741,
      dashboardPort: opts.dashboardPort ?? 4097,
      whisperModel: opts.whisperModel ?? "whisper-large-v3-turbo",
      modelProvider: opts.modelProvider,
      modelID: opts.modelID,
    })

    const registration = await ctx.rpc.register(Nokia, {
      status: async () => ({
        connected: bridge.state.connected,
        phone: bridge.state.phone,
        lastTranscript: bridge.state.lastTranscript,
        lastSessionID: bridge.state.lastSessionID,
        stage: bridge.state.stage,
        pct: bridge.state.pct,
        label: bridge.state.label,
      }),
      sendText: async (input, context) => {
        const { text } = input as { text: string }
        if (!text?.trim()) {
          return context.error("not_connected", "Empty text", { reason: "empty" })
        }
        const sessionID = await bridge.sendText(text.trim())
        return { sessionID }
      },
      interrupt: async (input) => {
        const { sessionID } = input as { sessionID: string }
        await bridge.interrupt(sessionID)
        return { ok: true }
      },
    })

    // Tool so agents / Web clients can check the phone link.
    await ctx.tool.transform((editor) => {
      editor.add({
        name: "nokia_status",
        description: "Check Nokia 6300 Bluetooth link, last transcript and job progress.",
        input: {
          type: "object",
          properties: {},
          additionalProperties: false,
        },
        execute: async () => ({
          content: JSON.stringify(
            {
              connected: bridge.state.connected,
              phone: bridge.state.phone,
              stage: bridge.state.stage,
              pct: bridge.state.pct,
              label: bridge.state.label,
              lastTranscript: bridge.state.lastTranscript,
              lastSessionID: bridge.state.lastSessionID,
            },
            null,
            2,
          ),
        }),
      })
    })

    // /nokia slash command: prompt OpenCode (and the phone sees progress).
    await ctx.command.transform((editor) => {
      editor.add({
        name: "nokia",
        description: "Send a prompt via the Nokia bridge (phone shows live progress)",
        execute: async ({ sessionID, prompt, delivery }) => {
          const text = prompt.text?.trim() || "Hello from Nokia bridge"
          await bridge.sendText(text)
          await ctx.session.prompt({ sessionID, text, delivery })
        },
      })
    })

    // Event subscription disabled: ctx.event.subscribe hangs/fails
    // silently on this server build; bridge progress is pushed to the
    // phone via direct PROGRESS lines instead.
    const controller = new AbortController()
    void controller

    return () => {
      controller.abort()
      bridge.stop()
      void registration.dispose()
    }
  },
})
