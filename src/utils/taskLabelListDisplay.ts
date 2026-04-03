import type { TaskLabel } from '../types/domain'

/** 与常见类型标签对齐的色点；其余名称稳定哈希成色 */
const LABEL_DOT_BY_NAME: Record<string, string> = {
  bug: '#e5484d',
  feature: '#6e56cf',
  improvement: '#29a3f2',
  chore: '#889096',
  docs: '#0091ff',
  performance: '#f76808'
}

export function labelListDotColor(name: string): string {
  const key = name.trim().toLowerCase()
  const mapped = LABEL_DOT_BY_NAME[key]
  if (mapped) return mapped
  let h = 0
  for (let i = 0; i < key.length; i++) {
    h = key.charCodeAt(i) + ((h << 5) - h)
  }
  const hue = Math.abs(h) % 360
  return `hsl(${hue} 48% 46%)`
}

export function sortedTaskLabelsForList(labels: TaskLabel[] | undefined): TaskLabel[] {
  if (labels == null || labels.length === 0) return []
  return [...labels].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  )
}
