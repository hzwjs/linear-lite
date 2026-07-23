import { describe, expect, it } from 'vitest'
import {
  AVATAR_PALETTE_15,
  AVATAR_BACKGROUND_PALETTE_15,
  getInitials,
  getAvatarColor,
  getAvatarColorByUsername,
  normalizeUsernameForAvatar
} from './avatar'

describe('getInitials', () => {
  it("'John Doe' → 'JD'", () => {
    expect(getInitials('John Doe')).toBe('JD')
  })

  it("'黄志文' → '志文'（中文取后两字）", () => {
    expect(getInitials('黄志文')).toBe('志文')
  })

  it("'张三' → '张三'（两字不变）", () => {
    expect(getInitials('张三')).toBe('张三')
  })

  it("'Alice' → 'AL'", () => {
    expect(getInitials('Alice')).toBe('AL')
  })

  it("'A' → 'A'", () => {
    expect(getInitials('A')).toBe('A')
  })

  it("'Unassigned' → '—'", () => {
    expect(getInitials('Unassigned')).toBe('—')
  })

  it("'' → '—'", () => {
    expect(getInitials('')).toBe('—')
  })

  it('single space-separated two words: take first letter of each word', () => {
    expect(getInitials('John Doe')).toBe('JD')
  })

  it('no space: CJK last 2 chars, Latin first 2 chars', () => {
    expect(getInitials('Alice')).toBe('AL')
    expect(getInitials('A')).toBe('A')
    expect(getInitials('黄志文')).toBe('志文')
  })
})

function channelToLinear(c: number): number {
  const x = c / 255
  return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)
}

function relativeLuminance(hex: string): number {
  const r = channelToLinear(parseInt(hex.slice(1, 3), 16))
  const g = channelToLinear(parseInt(hex.slice(3, 5), 16))
  const b = channelToLinear(parseInt(hex.slice(5, 7), 16))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrastRatio(foreground: string, background: string): number {
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

describe('getAvatarColorByUsername', () => {
  it('same username returns same colors', () => {
    const a = getAvatarColorByUsername('黄志文')
    const b = getAvatarColorByUsername('黄志文')
    expect(a).toEqual(b)
  })

  it('trim and case do not change mapping', () => {
    expect(getAvatarColorByUsername('  Alice  ')).toEqual(getAvatarColorByUsername('alice'))
    expect(getAvatarColorByUsername('Bob')).toEqual(getAvatarColorByUsername('BOB'))
  })

  it('NFKC normalizes fullwidth Latin to halfwidth for stable hash', () => {
    const full = getAvatarColorByUsername('\uff21lice')
    const half = getAvatarColorByUsername('Alice')
    expect(full).toEqual(half)
  })

  it('empty string uses fixed slot 0', () => {
    expect(getAvatarColorByUsername('')).toEqual(AVATAR_PALETTE_15[0])
    expect(getAvatarColorByUsername('   ')).toEqual(AVATAR_PALETTE_15[0])
  })

  it('foreground stays readable against its mapped background', () => {
    for (const pair of AVATAR_PALETTE_15) {
      expect(contrastRatio(pair.color, pair.background)).toBeGreaterThanOrEqual(4.5)
    }
  })
})

describe('normalizeUsernameForAvatar', () => {
  it('matches getAvatarColorByUsername keying', () => {
    const u = '  TeSt  '
    expect(normalizeUsernameForAvatar(u)).toBe('test')
  })
})

describe('AVATAR_BACKGROUND_PALETTE_15', () => {
  it('has 15 distinct backgrounds', () => {
    expect(AVATAR_BACKGROUND_PALETTE_15.length).toBe(15)
    expect(new Set(AVATAR_BACKGROUND_PALETTE_15).size).toBe(15)
  })

  it('keeps every avatar background visibly chromatic', () => {
    for (const { background } of AVATAR_PALETTE_15) {
      const channels = background
        .slice(1)
        .match(/../g)!
        .map((value) => parseInt(value, 16))
      expect(Math.max(...channels) - Math.min(...channels)).toBeGreaterThan(12)
    }
  })
})

describe('getAvatarColor (legacy userId)', () => {
  it('same userId returns same colors', () => {
    const a = getAvatarColor(1)
    const b = getAvatarColor(1)
    expect(a).toEqual(b)
  })

  it('maps to palette entries', () => {
    const a = getAvatarColor(1)
    const b = getAvatarColor(2)
    expect(AVATAR_BACKGROUND_PALETTE_15).toContain(a.background)
    expect(AVATAR_BACKGROUND_PALETTE_15).toContain(b.background)
  })

  it('uses a readable foreground', () => {
    const color = getAvatarColor(42)
    expect(contrastRatio(color.color, color.background)).toBeGreaterThanOrEqual(4.5)
  })
})
