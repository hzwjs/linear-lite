export type TaskLabelToneSource = { id?: number; name: string }

/** 与标签选择器共用的稳定色调映射，确保同一标签在所有界面使用同一语义色。 */
export function getTaskLabelTone(label: TaskLabelToneSource): number {
  const source = `${label.id ?? ''}:${label.name}`
  let hash = 0
  for (let i = 0; i < source.length; i += 1) hash = (hash * 31 + source.charCodeAt(i)) | 0
  return Math.abs(hash) % 6
}
