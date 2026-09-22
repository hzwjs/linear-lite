import test from "node:test"
import assert from "node:assert/strict"

import { createAdapter } from "../src/linear-lite-mcp-stdio-adapter.mjs"

function response(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

test("forwards Pi initialize through a 2025 MCP POST", async () => {
  const calls = []
  const adapter = createAdapter({
    endpoint: "https://linear-lite.example/mcp",
    tokenProvider: async () => "token",
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return response({
        jsonrpc: "2.0",
        id: "pi-stdio-adapter",
        result: {
          protocolVersion: "2025-06-18",
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "linear-lite", version: "1.0.0" },
        },
      })
    },
  })

  const result = await adapter.handle({
    jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" },
  })

  assert.equal(result.result.protocolVersion, "2025-06-18")
  assert.equal(calls.length, 1)
  assert.equal(calls[0].options.method, "POST")
  assert.equal(calls[0].options.headers["MCP-Protocol-Version"], "2025-06-18")
  assert.equal(calls[0].options.headers["Mcp-Method"], "initialize")
  const body = JSON.parse(calls[0].options.body)
  assert.equal(body.method, "initialize")
  assert.equal(body.params._meta["io.modelcontextprotocol/protocolVersion"], "2025-06-18")
})

test("forwards tools/call with the server method and tool-name headers", async () => {
  let call
  const adapter = createAdapter({
    endpoint: "https://linear-lite.example/mcp",
    tokenProvider: async () => "token",
    fetchImpl: async (_url, options) => {
      call = options
      return response({ jsonrpc: "2.0", id: "pi-stdio-adapter", result: { content: [] } })
    },
  })

  const result = await adapter.handle({
    jsonrpc: "2.0", id: "call-1", method: "tools/call", params: { name: "get_task", arguments: { taskKey: "LINEAR-LITE-1" } },
  })

  assert.deepEqual(result, { jsonrpc: "2.0", id: "call-1", result: { content: [] } })
  assert.equal(call.headers["Mcp-Method"], "tools/call")
  assert.equal(call.headers["Mcp-Name"], "get_task")
  assert.equal(call.headers.Authorization, "Bearer token")
  const body = JSON.parse(call.body)
  assert.deepEqual(body.params.arguments, { taskKey: "LINEAR-LITE-1" })
})

test("wraps array structuredContent for Pi direct-tool validation", async () => {
  const adapter = createAdapter({
    endpoint: "https://linear-lite.example/mcp",
    tokenProvider: async () => "token",
    fetchImpl: async () => response({
      jsonrpc: "2.0", id: "pi-stdio-adapter", result: { structuredContent: [{ id: 1 }], content: [] },
    }),
  })

  const result = await adapter.handle({
    jsonrpc: "2.0", id: "call-2", method: "tools/call", params: { name: "list_projects", arguments: {} },
  })

  assert.deepEqual(result.result.structuredContent, { items: [{ id: 1 }] })
})

test("does not emit a response for Pi initialized notifications", async () => {
  const adapter = createAdapter({
    endpoint: "https://linear-lite.example/mcp",
    tokenProvider: async () => "token",
    fetchImpl: async () => { throw new Error("不应调用远程服务") },
  })

  assert.equal(await adapter.handle({ jsonrpc: "2.0", method: "notifications/initialized" }), null)
})
