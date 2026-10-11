import { Rpc } from "@opencode/plugin/rpc"

/**
 * Shared RPC contract (id "nokia").
 * Implemented by src/index.ts (server), consumed by src/tui.tsx,
 * web/dashboard.html, or any HTTP client via POST /api/rpc/nokia/<method>.
 */
export const Nokia = Rpc.define({
  id: "nokia",
  methods: {
    status: {
      input: {
        type: "object",
        properties: {},
        additionalProperties: false,
      } as const,
      output: {
        type: "object",
        properties: {
          connected: { type: "boolean" },
          phone: { type: "string" },
          lastTranscript: { type: "string" },
          lastSessionID: { type: "string" },
          stage: { type: "string" },
          pct: { type: "number" },
          label: { type: "string" },
        },
        required: [
          "connected",
          "phone",
          "lastTranscript",
          "lastSessionID",
          "stage",
          "pct",
          "label",
        ],
        additionalProperties: false,
      } as const,
    },
    sendText: {
      input: {
        type: "object",
        properties: { text: { type: "string" } },
        required: ["text"],
        additionalProperties: false,
      } as const,
      output: {
        type: "object",
        properties: { sessionID: { type: "string" } },
        required: ["sessionID"],
        additionalProperties: false,
      } as const,
      errors: {
        not_connected: {
          type: "object",
          properties: { reason: { type: "string" } },
          required: ["reason"],
          additionalProperties: false,
        } as const,
      },
    },
    interrupt: {
      input: {
        type: "object",
        properties: { sessionID: { type: "string" } },
        required: ["sessionID"],
        additionalProperties: false,
      } as const,
      output: {
        type: "object",
        properties: { ok: { type: "boolean" } },
        required: ["ok"],
        additionalProperties: false,
      } as const,
    },
  },
  events: {
    progress: {
      schema: {
        type: "object",
        properties: {
          stage: { type: "string" },
          pct: { type: "number" },
          label: { type: "string" },
        },
        required: ["stage", "pct", "label"],
        additionalProperties: false,
      } as const,
    },
    transcript: {
      schema: {
        type: "object",
        properties: { text: { type: "string" } },
        required: ["text"],
        additionalProperties: false,
      } as const,
    },
    answer: {
      schema: {
        type: "object",
        properties: {
          sessionID: { type: "string" },
          line: { type: "string" },
          done: { type: "boolean" },
        },
        required: ["sessionID", "line", "done"],
        additionalProperties: false,
      } as const,
    },
  },
})
