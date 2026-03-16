/**
 * 无 avatar_url 时用姓名生成 initials（最多 2 个字符）。
 * 空或 'Unassigned' 返回 '—'；有空格取两词首字母；无空格取前 2 字符（或 1）。
 */
export function getInitials(name: string): string {
  const trimmed = name.trim()
  if (!trimmed || trimmed === 'Unassigned') return '—'
  const parts = trimmed.split(/\s+/)
  if (parts.length >= 2) {
    const first = parts[0][0] ?? ''
    const second = parts[1][0] ?? ''
    return (first + second).toUpperCase()
  }
  return trimmed.length >= 2 ? trimmed.slice(0, 2).toUpperCase() : trimmed
}

/**
 * HSL 转 hex（S、L 为 0–100）
 */
function hslToHex(h: number, s: number, l: number): string {
  s /= 100
  l /= 100
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + h / 30) % 12
    const c = l - a * Math.max(Math.min(k - 3, 9 - k, 1), -1)
    return Math.round(c * 255)
      .toString(16)
      .padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

/**
 * 按 userId 生成稳定背景色和对比文字色。
 * 背景偏暗则 color 为 #fff，否则 #374151。
 */
export function getAvatarColor(userId: number): { background: string; color: string } {
  const hue = ((userId * 2654435761) >>> 0) % 360
  const s = 65
  const l = 45
  const background = hslToHex(hue, s, l)
  const color = l <= 50 ? '#fff' : '#374151'
  return { background, color }
}
