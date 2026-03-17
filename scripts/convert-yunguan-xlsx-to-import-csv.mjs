/**
 * 将「运管平台任务计划表」xlsx 转为系统标准导入 CSV，便于在页面上传。
 *
 * 用法（项目根目录执行）：
 *   node scripts/convert-yunguan-xlsx-to-import-csv.mjs docs/tmp/运管平台任务计划表_重构版v2.xlsx
 *
 * 输出：同目录下 运管平台任务计划表_标准导入.csv（可通过看板「导入」选择该文件上传）。
 */

import fs from 'fs'
import path from 'path'
import { createRequire } from 'module'
const require = createRequire(import.meta.url)
const XLSX = require('xlsx')

const STATUS_MAP = {
  已完成: 'done',
  进行中: 'in_progress',
  未开始: 'backlog'
}

const HEADER_ROW_INDEX = 3
const DATA_START_INDEX = 4
const TASK_ID_PATTERN = /^T\d+/

// 系统标准表头，与 getTaskImportTemplateCsv() 一致
const STANDARD_HEADER = 'title,importId,parentImportId,description,status,priority,assignee,dueDate'

function escapeCsvCell(value) {
  if (value == null) return ''
  const s = String(value)
  if (s.includes(',') || s.includes('"') || s.includes('\n') || s.includes('\r')) {
    return '"' + s.replace(/"/g, '""') + '"'
  }
  return s
}

function parseXlsx(filePath) {
  const buf = fs.readFileSync(filePath)
  const wb = XLSX.read(buf, { type: 'buffer' })
  const sheet = wb.Sheets[wb.SheetNames[0]]
  const matrix = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' })
  const headers = (matrix[HEADER_ROW_INDEX] || []).map((c) => String(c ?? '').trim())
  const col = (name) => headers.indexOf(name)

  const idxId = col('任务ID')
  const idxModule = col('所属模块')
  const idxTitle = col('任务名称')
  const idxOwner = col('责任人')
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

    let dueDate = ''
    if (idxEnd >= 0) {
      const end = String(row[idxEnd] ?? '').trim()
      if (/^\d{4}-\d{2}-\d{2}$/.test(end)) dueDate = end
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

    const assignee = idxOwner >= 0 ? String(row[idxOwner] ?? '').trim() : ''

    rows.push({
      title,
      importId: id,
      parentImportId: '',
      description,
      status,
      priority: 'medium',
      assignee,
      dueDate
    })
  }

  return rows
}

function toCsv(rows) {
  const lines = [STANDARD_HEADER]
  for (const r of rows) {
    const cells = [
      r.title,
      r.importId,
      r.parentImportId,
      r.description,
      r.status,
      r.priority,
      r.assignee,
      r.dueDate
    ]
    lines.push(cells.map(escapeCsvCell).join(','))
  }
  return lines.join('\n')
}

function main() {
  const filePath = process.argv[2]
  if (!filePath) {
    console.error('用法: node scripts/convert-yunguan-xlsx-to-import-csv.mjs <xlsx路径>')
    process.exit(1)
  }

  const absPath = path.isAbsolute(filePath) ? filePath : path.join(process.cwd(), filePath)
  if (!fs.existsSync(absPath)) {
    console.error('文件不存在:', absPath)
    process.exit(1)
  }

  const rows = parseXlsx(absPath)
  if (rows.length === 0) {
    console.log('无有效任务行')
    process.exit(0)
  }

  const dir = path.dirname(absPath)
  const base = path.basename(absPath, path.extname(absPath))
  const outPath = path.join(dir, base + '_标准导入.csv')
  const csv = toCsv(rows)
  fs.writeFileSync(outPath, '\uFEFF' + csv, 'utf8')
  console.log('已写入:', outPath, '共', rows.length, '行')
}

main()
