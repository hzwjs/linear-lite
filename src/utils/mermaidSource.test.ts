import { describe, expect, it } from 'vitest'
import { normalizeMermaidDiagramSource } from './mermaidSource'

describe('normalizeMermaidDiagramSource', () => {
  it('保留无围栏的 sequenceDiagram 正文', () => {
    const s = 'sequenceDiagram\n  A->>B: hi'
    expect(normalizeMermaidDiagramSource(s)).toBe(s)
  })

  it('去掉整段 ```mermaid ... ``` 围栏', () => {
    const raw = '```mermaid\nsequenceDiagram\nA->>B\n```'
    expect(normalizeMermaidDiagramSource(raw)).toBe('sequenceDiagram\nA->>B')
  })
})
