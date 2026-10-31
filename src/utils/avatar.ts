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
 * 15 组固定头像配色：OKLCH 色相每 24° 一档均匀分布（15° 起），
 * 背景统一 L=0.92 / C≈0.055，文字统一 L=0.44 / C≈0.13（超出 sRGB 时降低纯度），
 * 保证各色明度一致、色相不扎堆，文字对比度均 ≥ 5.8:1。
 */
export const AVATAR_PALETTE_15 = Object.freeze([
  { background: '#ffdadc', color: '#8c2b3b' }, // 15° 玫红
  { background: '#fedcd1', color: '#8b310f' }, // 39° 珊瑚
  { background: '#ffdec1', color: '#784401' }, // 63° 橙
  { background: '#f4e3bc', color: '#684e00' }, // 87° 琥珀
  { background: '#e6e9bf', color: '#555701' }, // 111° 橄榄
  { background: '#d5edc9', color: '#306101' }, // 135° 草绿
  { background: '#c6f0d7', color: '#03633e' }, // 159° 翠绿
  { background: '#bdf1e8', color: '#016057' }, // 183° 青绿
  { background: '#baf0f7', color: '#055e67' }, // 207° 青
  { background: '#c6ebff', color: '#025a7a' }, // 231° 天蓝
  { background: '#d4e6ff', color: '#135298' }, // 255° 蓝
  { background: '#dee3ff', color: '#454798' }, // 279° 靛
  { background: '#ebdeff', color: '#623c8c' }, // 303° 紫
  { background: '#f9d8f8', color: '#763377' }, // 327° 品红
  { background: '#ffd8e8', color: '#842d5b' } // 351° 粉
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
