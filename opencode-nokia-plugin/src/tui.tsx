import { Plugin, usePlugin } from "@opencode/plugin/tui"
import { Nokia } from "opencode-nokia-plugin/rpc"
import { Show, createSignal, onCleanup } from "solid-js"

/**
 * TUI plugin for the Nokia 6300 bridge.
 * Sidebar status + /nokia route + palette commands + toasts.
 * Load via cli.json: { "plugins": ["./opencode-nokia-plugin"] }
 * (the package's ./tui export is picked up automatically).
 */
export default Plugin.define({
  id: "nokia-bridge.cli",
  setup(context) {
    const location =
      context.location ?? context.data.location.default()
    const rpc = context.client.rpc(Nokia)

    const [status, setStatus] = context.storage.memory("nokia-status", {
      initial: {
        connected: false,
        phone: "",
        stage: "IDLE",
        pct: 0,
        label: "…",
        lastTranscript: "",
      },
    })

    async function refresh() {
      try {
        const s = (await rpc.status(
          {},
          { location } as any,
        )) as any
        setStatus((draft: any) => {
          draft.connected = s.connected
          draft.phone = s.phone
          draft.stage = s.stage
          draft.pct = s.pct
          draft.label = s.label
          draft.lastTranscript = s.lastTranscript
        })
      } catch {}
    }

    void refresh()
    const timer = setInterval(() => void refresh(), 5000)
    onCleanup(() => clearInterval(timer))

    // Live toasts from bridge events.
    const offProgress = rpc.events.on("progress", (ev: any) => {
      const d = ev.data as { stage: string; pct: number; label: string }
      setStatus((draft: any) => {
        draft.stage = d.stage
        draft.pct = d.pct
        draft.label = d.label
      })
      if (d.stage === "DONE") {
        context.ui.toast.show({
          title: "Nokia 6300",
          message: `Done: ${d.label}`,
          variant: "success",
        })
      } else if (d.stage === "ERROR") {
        context.ui.toast.show({
          title: "Nokia 6300",
          message: d.label,
          variant: "error",
        })
      }
    })
    const offTranscript = rpc.events.on("transcript", (ev: any) => {
      const text = (ev.data as { text: string }).text
      setStatus((draft: any) => {
        draft.lastTranscript = text
      })
      context.ui.toast.show({
        title: "Nokia heard",
        message: text.slice(0, 120),
        variant: "info",
      })
    })
    onCleanup(() => {
      offProgress()
      offTranscript()
    })

    // Sidebar status line.
    const unregisterSlot = context.ui.slot({
      append: "sidebar.footer",
      render: () => StatusLine(),
    })

    // Full page route.
    const unregisterRoute = context.ui.router.register({
      name: "nokia",
      render: () => NokiaPage(),
    })

    // Palette / slash / keymap commands.
    context.keymap.layer(() => ({
      mode: "global" as const,
      commands: [
        {
          id: "nokia.status",
          title: "Nokia: show link status",
          group: "Nokia 6300",
          palette: true,
          slash: { name: "nokia-status", aliases: ["nokia"] },
          run: async () => {
            await refresh()
            const s = status
            await context.ui.dialog.alert({
              title: "Nokia 6300",
              message:
                `Link: ${s.connected ? "● " + s.phone : "○ disconnected"}\n` +
                `Stage: ${s.stage} ${s.pct}% ${s.label}\n` +
                `Heard: ${s.lastTranscript || "—"}`,
            })
          },
        },
        {
          id: "nokia.send",
          title: "Nokia: send prompt (phone shows progress)",
          group: "Nokia 6300",
          palette: true,
          slash: { name: "nokia-send", arguments: true },
          run: async (input: any) => {
            const text =
              typeof input === "string" && input.trim()
                ? input
                : await context.ui.dialog.prompt({
                    title: "Send to OpenCode",
                    placeholder: "list files in src/",
                  })
            if (!text) return
            await rpc.sendText({ text } as any, { location } as any)
            context.ui.toast.show({
              title: "Nokia bridge",
              message: "Sent — watch the 6300 for progress.",
              variant: "success",
            })
          },
        },
        {
          id: "nokia.page",
          title: "Nokia: open remote page",
          group: "Nokia 6300",
          palette: true,
          bind: "ctrl+g",
          run: () => {
            context.ui.router.navigate({ type: "plugin", name: "nokia" })
          },
        },
      ],
    }))

    function StatusLine() {
      return (
        <text>
          {`BT:6300 ${status.connected ? "●" : "○"} ${status.stage} ${status.pct}%`}
        </text>
      )
    }

    function NokiaPage() {
      const ctx = usePlugin()
      return (
        <box flexDirection="column" padding={1} gap={1}>
          <text fg={ctx.theme.text.base}>Nokia 6300 Remote</text>
          <text>
            {`Link: ${status.connected ? "connected (" + status.phone + ")" : "disconnected"}`}
          </text>
          <text>{`Stage: ${status.stage} ${status.pct}% — ${status.label}`}</text>
          <text>{`Heard: ${status.lastTranscript || "—"}`}</text>
          <text>Record a voice note on the phone, or use palette: Nokia: send prompt.</text>
        </box>
      )
    }

    return () => {
      unregisterSlot()
      unregisterRoute()
    }
  },
})
