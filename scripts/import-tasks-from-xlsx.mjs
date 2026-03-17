/**
 * 从「运管平台任务计划表」格式的 xlsx 导入任务到 Linear Lite。
 *
 * 用法（在项目根目录执行）：
 *   PROJECT_ID=1 JWT=<token> node scripts/import-tasks-from-xlsx.mjs docs/tmp/运管平台任务计划表_重构版v2.xlsx
 *
 * 或先登录获取 token（需配置 API_BASE_URL、EMAIL、PASSWORD）：
 *   API_BASE_URL=http://localhost:5173/api EMAIL=xxx PASSWORD=xxx PROJECT_ID=1 node scripts/import-tasks-from-xlsx.mjs docs/tmp/运管平台任务计划表_重构版v2.xlsx
 *
 * 环境变量：
 *   PROJECT_ID  必填。目标项目 ID。
 *   JWT         可选。Bearer token；未提供时若提供 EMAIL+PASSWORD 会先调用登录接口。
 *   API_BASE_URL 可选。默认 http://localhost:5173/api（走 Vite 代理时用此即可）。
 *   EMAIL, PASSWORD 可选。用于登录获取 JWT。
 */

import fs from 'fs'
import path from 'path'
import { createRequire } from 'module'
const require = createRequire(import.meta.url)
const XLSX = require('xlsx')

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:5173/api'
const PROJECT_ID = process.env.PROJECT_ID
const JWT = process.env.JWT
const EMAIL = process.env.EMAIL
const PASSWORD = process.env.PASSWORD

const STATUS_MAP = {
  已完成: 'done',
  进行中: 'in_progress',
  未开始: 'backlog'
}

const HEADER_ROW_INDEX = 3
const DATA_START_INDEX = 4
const TASK_ID_PATTERN = /^T\d+/

function parseXlsx(filePath) {
  const buf = fs.readFileSync(filePath)
  const wb = XLSX.read(buf, { type: 'buffer' })
  const sheet = wb.Sheets[wb.SheetNames[0]]
  const matrix = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' })
  const headers = (matrix[HEADER_ROW_INDEX] || []).map((c) => String(c ?? '').trim())
  const col = (name) => {
    const i = headers.indexOf(name)
    return i >= 0 ? i : -1
  }
  const idxId = col('任务ID')
  const idxModule = col('所属模块')
  const idxTitle = col('任务名称')
  const idxOwner = col('责任人')
  const idxStart = col('开始日期')
  const idxEnd = col('结束日期')
  const idxStatus = col('状态')
  const idxNote = col('备注')

  if (idxId < 0 || idxTitle < 0) {
    throw new Error('表头需包含「任务ID」「任务名称」。当前表头: ' + headers.join(', '))
  }

  const rows = []
  for (let i = DATA_START_INDEX; i < matrix.length; i++) {
    const row = matrix[i] || []
    const id = String(row[idxId] ?? '').trim()
    if (!id || !TASK_ID_PATTERN.test(id)) continue

    const title = String(row[idxTitle] ?? '').trim()
    if (!title) continue

    const statusRaw = idxStatus >= 0 ? String(row[idxStatus] ?? '').trim() : ''
    const status = STATUS_MAP[statusRaw] || 'backlog'

    let dueDate = null
    if (idxEnd >= 0) {
      const end = String(row[idxEnd] ?? '').trim()
      if (/^\d{4}-\d{2}-\d{2}$/.test(end)) dueDate = end + 'T00:00:00'
    }

    const parts = []
    if (idxModule >= 0) {
      const mod = String(row[idxModule] ?? '').trim()
      if (mod) parts.push(`所属模块: ${mod}`)
    }
    if (idxNote >= 0) {
      const note = String(row[idxNote] ?? '').trim()
      if (note) parts.push(note)
    }
    const description = parts.length ? parts.join('\n') : ''

    rows.push({
      lineNumber: i + 1,
      importId: id,
      parentImportId: null,
      title,
      description: description || undefined,
      status,
      priority: 'medium',
      assigneeId: null,
      dueDate
    })
  }

  return rows
}

async function getToken() {
  if (JWT) return JWT
  if (EMAIL && PASSWORD) {
    const res = await fetch(`${API_BASE_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: EMAIL, password: PASSWORD })
    })
    const data = await res.json()
    if (data.code !== 200 || !data.data?.token) throw new Error('登录失败: ' + (data.message || res.status))
    return data.data.token
  }
  return null
}

async function importTasks(rows) {
  const token = await getToken()
  if (!token) throw new Error('请设置 JWT 或 EMAIL+PASSWORD')

  const body = {
    projectId: Number(PROJECT_ID),
    rows
  }

  const res = await fetch(`${API_BASE_URL}/tasks/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify(body)
  })

  const data = await res.json()
  if (data.code !== 200) throw new Error(data.message || `HTTP ${res.status}`)
  return data.data
}

async function main() {
  const args = process.argv.slice(2)
  const dryRun = args.includes('--dry-run')
  const filePath = args.find((a) => !a.startsWith('--'))
  if (!filePath) {
    console.error('用法: PROJECT_ID=1 JWT=<token> node scripts/import-tasks-from-xlsx.mjs <xlsx路径> [--dry-run]')
    process.exit(1)
  }
  if (!dryRun && !PROJECT_ID) {
    console.error('请设置环境变量 PROJECT_ID')
    process.exit(1)
  }

  const absPath = path.isAbsolute(filePath) ? filePath : path.join(process.cwd(), filePath)
  if (!fs.existsSync(absPath)) {
    console.error('文件不存在:', absPath)
    process.exit(1)
  }

  console.log('解析文件:', absPath)
  const rows = parseXlsx(absPath)
  console.log('解析到任务行数:', rows.length)
  if (rows.length === 0) {
    console.log('无有效任务行，退出')
    process.exit(0)
  }

  if (dryRun) {
    console.log('--dry-run: 仅解析，不请求 API。前 3 条:', JSON.stringify(rows.slice(0, 3), null, 2))
    process.exit(0)
  }

  console.log('调用导入接口...')
  const result = await importTasks(rows)
  console.log('导入结果:', result)
  console.log('创建数量:', result.createdCount, '父任务:', result.parentCount, '子任务:', result.subtaskCount)
  if (result.taskKeys && result.taskKeys.length) {
    console.log('前 10 个 taskKey:', result.taskKeys.slice(0, 10).join(', '))
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
