/** 是否包含 CJK 字符（用于中文名取“名”即后两字） */
function hasCjk(str: string): boolean {
  return /[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]/.test(str)
}

/**
 * 无 avatar_url 时用姓名生成 initials（最多 2 个字符）。
 * 空或 'Unassigned' 返回 '—'；有空格取两词首字母；无空格：中文取后 2 字（志文），英文取前 2 字母。
 */
export function getInitials(name: string): string {
  const trimmed = name.trim()
  if (!trimmed || trimmed === 'Unassigned') return '—'
  const parts = trimmed.split(/\s+/)
  if (parts.length >= 2) {
    const first = parts[0]?.[0] ?? ''
    const second = parts[1]?.[0] ?? ''
    return (first + second).toUpperCase()
  }
  if (trimmed.length < 2) return trimmed
  if (hasCjk(trimmed)) return trimmed.slice(-2)
  return trimmed.slice(0, 2).toUpperCase()
}

/**
 * 15 组固定头像配色（参考 Linear 的柔和色面与高可读文字组合）。
 * 背景降低纯度，文字使用对应深色，避免头像像高亮标签或带白描边。
 */
export const AVATAR_PALETTE_15 = Object.freeze([
  { background: '#dedcff', color: '#4338a0' },
  { background: '#eadcff', color: '#6b21a8' },
  { background: '#fbd5ea', color: '#9d174d' },
  { background: '#dbeafe', color: '#1d4ed8' },
  { background: '#cffafe', color: '#0e7490' },
  { background: '#ccfbf1', color: '#0f766e' },
  { background: '#dcfce7', color: '#166534' },
  { background: '#fef3c7', color: '#92400e' },
  { background: '#ffedd5', color: '#9a3412' },
  { background: '#fee2e2', color: '#b91c1c' },
  { background: '#ffe4e6', color: '#9f1239' },
  { background: '#fce7f3', color: '#9d174d' },
  { background: '#ede9fe', color: '#5b21b6' },
  { background: '#e0e7ff', color: '#3730a3' },
  { background: '#fae8ff', color: '#86198f' }
])

/** 保留背景色表供现有展示与测试使用。 */
export const AVATAR_BACKGROUND_PALETTE_15: readonly string[] = Object.freeze(
  AVATAR_PALETTE_15.map(({ background }) => background)
)

/** 空用户名（归一化后）固定使用槽位 0，保证稳定兜底 */
const EMPTY_USERNAME_PALETTE_INDEX = 0

/** FNV-1a 32-bit，跨运行稳定 */
function fnv1a32(str: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h >>> 0
}

/**
 * 映射前归一化：trim → 小写 → Unicode NFKC（全角等折叠）。
 * 与 `getAvatarColorByUsername` 共用，单测锁定行为以免后续漂移。
 */
export function normalizeUsernameForAvatar(username: string): string {
  return username.trim().toLowerCase().normalize('NFKC')
}

/**
 * 按 **username** 生成稳定 fallback 头像背景与前景色（主入口）。
 * 同一用户名跨页面、跨会话颜色一致；改名后颜色可能变化（本期接受）。
 */
export function getAvatarColorByUsername(username: string): { background: string; color: string } {
  const key = normalizeUsernameForAvatar(username)
  if (!key) {
    return AVATAR_PALETTE_15[EMPTY_USERNAME_PALETTE_INDEX] ?? AVATAR_PALETTE_15[0]!
  }
  const idx = fnv1a32(key) % AVATAR_BACKGROUND_PALETTE_15.length
  return AVATAR_PALETTE_15[idx] ?? AVATAR_PALETTE_15[0]!
}

/**
 * 按 userId 的过渡兼容入口（与 username 映射独立，避免与数字用户名撞哈希）。
 * 新代码请优先使用 `getAvatarColorByUsername`。
 */
export function getAvatarColor(userId: number): { background: string; color: string } {
  const id = Number.isFinite(userId) ? Math.max(0, Math.floor(userId)) : 0
  const idx = fnv1a32(`\0legacy-uid:${id}`) % AVATAR_BACKGROUND_PALETTE_15.length
  return AVATAR_PALETTE_15[idx] ?? AVATAR_PALETTE_15[0]!
}
