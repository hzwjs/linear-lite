#!/usr/bin/env node

import { createInterface } from "node:readline"
import { spawn } from "node:child_process"
import { pathToFileURL } from "node:url"

const PI_PROTOCOL_VERSION = "2025-06-18"
const DEFAULT_REMOTE_PROTOCOL_VERSION = "2025-06-18"
const DEFAULT_TIMEOUT_MS = 30_000

function jsonRpcError(id, code, message) {
  return { jsonrpc: "2.0", id: id ?? null, error: { code, message } }
}

function isRequestId(id) {
  return typeof id === "string" || Number.isInteger(id)
}

function readToken(command) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, { shell: true, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = ""
    let stderr = ""
    child.stdout.on("data", (chunk) => { stdout += chunk })
    child.stderr.on("data", (chunk) => { stderr += chunk })
    child.on("error", reject)
    child.on("close", (code) => {
      const token = stdout.trim()
      if (code !== 0 || !token) {
        reject(new Error(stderr.trim() || "读取 Linear Lite MCP 令牌失败"))
        return
      }
      resolve(token)
    })
  })
}

function serverMeta(protocolVersion) {
  return {
    "io.modelcontextprotocol/protocolVersion": protocolVersion,
    "io.modelcontextprotocol/clientCapabilities": {},
    "io.modelcontextprotocol/clientInfo": {
      name: "linear-lite-pi-stdio-adapter",
      version: "1.0.0",
    },
  }
}

function normalizeForPi(result) {
  if (Array.isArray(result?.structuredContent)) {
    return { ...result, structuredContent: { items: result.structuredContent } }
  }
  return result
}

export function createAdapter({
  endpoint,
  tokenProvider,
  remoteProtocolVersion = DEFAULT_REMOTE_PROTOCOL_VERSION,
  fetchImpl = fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  if (!endpoint) throw new Error("缺少 LINEAR_LITE_MCP_URL")
  if (typeof tokenProvider !== "function") throw new Error("缺少 Linear Lite MCP 令牌提供器")

  async function remoteCall(method, params, name) {
    const token = await tokenProvider()
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const requestParams = { ...params, _meta: serverMeta(remoteProtocolVersion) }
      const headers = {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        "MCP-Protocol-Version": remoteProtocolVersion,
        "Mcp-Method": method,
      }
      if (name) headers["Mcp-Name"] = name
      const response = await fetchImpl(endpoint, {
        method: "POST",
        headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: "pi-stdio-adapter", method, params: requestParams }),
        signal: controller.signal,
      })
      const payload = await response.json().catch(() => null)
      if (!response.ok || !payload) {
        throw new Error(payload?.error?.message || `Linear Lite MCP 返回 HTTP ${response.status}`)
      }
      if (payload.error) throw new Error(payload.error.message || "Linear Lite MCP 协议错误")
      return payload.result
    } finally {
      clearTimeout(timer)
    }
  }

  async function handle(request) {
    if (!request || typeof request !== "object" || Array.isArray(request)) {
      return jsonRpcError(null, -32600, "JSON-RPC 请求必须是对象")
    }
    const { id, method } = request
    if (request.jsonrpc !== "2.0" || typeof method !== "string") {
      return jsonRpcError(isRequestId(id) ? id : null, -32600, "无效的 JSON-RPC 请求")
    }
    if (method === "notifications/initialized" && (id === undefined || id === null)) return null
    if (!isRequestId(id)) return jsonRpcError(null, -32600, "无效的 JSON-RPC 请求")
    const params = request.params && typeof request.params === "object" && !Array.isArray(request.params)
      ? request.params
      : {}

    try {
      if (method === "initialize") {
        const initialized = await remoteCall("initialize", {
          protocolVersion: PI_PROTOCOL_VERSION,
          capabilities: params.capabilities ?? {},
          clientInfo: params.clientInfo ?? { name: "pi", version: "unknown" },
        })
        return { jsonrpc: "2.0", id, result: initialized }
      }
      if (method === "tools/list") {
        return { jsonrpc: "2.0", id, result: await remoteCall("tools/list", {}) }
      }
      if (method === "tools/call") {
        if (typeof params.name !== "string" || !params.name) {
          return jsonRpcError(id, -32602, "tools/call 缺少 name")
        }
        return {
          jsonrpc: "2.0",
          id,
          result: normalizeForPi(await remoteCall(
            "tools/call", { name: params.name, arguments: params.arguments ?? {} }, params.name,
          )),
        }
      }
      return jsonRpcError(id, -32601, `不支持的 Pi MCP 方法: ${method}`)
    } catch (error) {
      return jsonRpcError(id, -32000, error instanceof Error ? error.message : "Linear Lite MCP 调用失败")
    }
  }

  return { handle }
}

async function main() {
  const endpoint = process.env.LINEAR_LITE_MCP_URL
  const tokenCommand = process.env.LINEAR_LITE_MCP_TOKEN_COMMAND
  if (!tokenCommand) throw new Error("缺少 LINEAR_LITE_MCP_TOKEN_COMMAND")
  const adapter = createAdapter({ endpoint, tokenProvider: () => readToken(tokenCommand) })
  const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
  for await (const line of input) {
    if (!line.trim()) continue
    let request
    try {
      request = JSON.parse(line)
    } catch {
      process.stdout.write(`${JSON.stringify(jsonRpcError(null, -32700, "JSON 解析失败"))}\n`)
      continue
    }
    const response = await adapter.handle(request)
    if (response) process.stdout.write(`${JSON.stringify(response)}\n`)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`linear-lite MCP 适配器启动失败: ${error.message}\n`)
    process.exitCode = 1
  })
}
