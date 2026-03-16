import { describe, expect, it } from 'vitest'
import { getInitials, getAvatarColor } from './avatar'

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

describe('getAvatarColor', () => {
  it('same userId returns same background and color on multiple calls', () => {
    const a = getAvatarColor(1)
    const b = getAvatarColor(1)
    expect(a.background).toBe(b.background)
    expect(a.color).toBe(b.color)
  })

  it('different userIds return different background', () => {
    const a = getAvatarColor(1)
    const b = getAvatarColor(2)
    expect(a.background).not.toBe(b.background)
  })

  it('color is #fff or dark gray for contrast', () => {
    const result = getAvatarColor(42)
    const validColors = ['#fff', '#ffffff', '#374151']
    expect(validColors).toContain(result.color)
  })
})
