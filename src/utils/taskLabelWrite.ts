import type { TaskLabelWriteItem } from '../services/api/types'

/**
 * 将侧栏/列表中的标签 chip 转为 API 写入项。
 * 乐观更新会用负数 id 占位；提交时必须按 name 走 find-or-create，不能发无效 id。
 */
export function toLabelWriteItem(chip: { id?: number; name: string }): TaskLabelWriteItem | null {
  const name = chip.name.trim()
  if (!name) return null
  if (chip.id != null && chip.id > 0) return { id: chip.id }
  return { name }
}

export function toLabelWriteItems(rows: { id?: number; name: string }[]): TaskLabelWriteItem[] {
  const out: TaskLabelWriteItem[] = []
  for (const row of rows) {
    const item = toLabelWriteItem(row)
    if (item) out.push(item)
  }
  return out
}
