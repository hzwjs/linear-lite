import { describe, expect, it } from 'vitest'
import {
  buildDocumentMinimapSegments,
  clampDocumentScrollTop,
  closestDocumentMinimapSegmentIndex,
  closestDocumentMinimapTickIndex,
  documentKeyboardScrollTop,
  documentMinimapTickPositions,
  documentMinimapTickWidth,
  documentScrollProgress,
  documentScrollTopForRailPointer
} from './documentMinimap'

describe('document minimap geometry', () => {
  it('centers the viewport on the selected rail position and clamps both ends', () => {
    expect(documentScrollTopForRailPointer(0, 200, 2000, 500)).toBe(0)
    expect(documentScrollTopForRailPointer(100, 200, 2000, 500)).toBe(750)
    expect(documentScrollTopForRailPointer(200, 200, 2000, 500)).toBe(1500)
  })

  it('reports progress against the actual scrollable range', () => {
    expect(documentScrollProgress(0, 2000, 500)).toBe(0)
    expect(documentScrollProgress(750, 2000, 500)).toBe(50)
    expect(documentScrollProgress(1500, 2000, 500)).toBe(100)
  })

  it('supports keyboard line, page and boundary navigation', () => {
    expect(documentKeyboardScrollTop('ArrowDown', 0, 2000, 500)).toBe(60)
    expect(documentKeyboardScrollTop('PageDown', 0, 2000, 500)).toBe(410)
    expect(documentKeyboardScrollTop('Home', 900, 2000, 500)).toBe(0)
    expect(documentKeyboardScrollTop('End', 0, 2000, 500)).toBe(1500)
    expect(documentKeyboardScrollTop('Escape', 0, 2000, 500)).toBeNull()
  })

  it('handles documents without a scrollable range', () => {
    expect(clampDocumentScrollTop(120, 400, 500)).toBe(0)
    expect(documentScrollTopForRailPointer(50, 100, 400, 500)).toBe(0)
  })

  it('groups a heading with nearby body blocks instead of rendering every block', () => {
    const segments = buildDocumentMinimapSegments([
      { top: 0, height: 40, indent: 0, emphasized: true, text: 'Overview' },
      { top: 52, height: 36, indent: 0, emphasized: false, text: 'First paragraph' },
      { top: 100, height: 36, indent: 0, emphasized: false, text: 'Second paragraph' },
      { top: 260, height: 40, indent: 0, emphasized: true, text: 'Details' },
      { top: 312, height: 36, indent: 0, emphasized: false, text: 'Detail paragraph' }
    ], 1000)

    expect(segments).toHaveLength(2)
    expect(segments[0]?.texts).toEqual(['Overview', 'First paragraph', 'Second paragraph'])
    expect(segments[1]?.texts).toEqual(['Details', 'Detail paragraph'])
  })

  it('caps the rail density for very long documents', () => {
    const blocks = Array.from({ length: 80 }, (_, index) => ({
      top: index * 100,
      height: 30,
      indent: 0,
      emphasized: true,
      text: `Heading ${index}`
    }))
    expect(buildDocumentMinimapSegments(blocks, 8000)).toHaveLength(10)
  })

  it('finds the semantic segment nearest to a document position', () => {
    const segments = buildDocumentMinimapSegments([
      { top: 0, height: 100, indent: 0, emphasized: true, text: 'Start' },
      { top: 500, height: 100, indent: 0, emphasized: true, text: 'End' }
    ], 1000)
    expect(closestDocumentMinimapSegmentIndex(segments, 80)).toBe(0)
    expect(closestDocumentMinimapSegmentIndex(segments, 530)).toBe(1)
  })

  it('keeps compact rail ticks separated and pointer selection aligned', () => {
    const segments = [
      { top: 0, height: 20, indent: 0, emphasized: true, texts: ['One'] },
      { top: 1, height: 20, indent: 0, emphasized: false, texts: ['Two'] },
      { top: 2, height: 20, indent: 0, emphasized: false, texts: ['Three'] }
    ]
    const positions = documentMinimapTickPositions(segments, 100)
    expect(positions).toEqual([1, 50, 99])
    expect(closestDocumentMinimapTickIndex(positions, 51)).toBe(1)
  })

  it('creates a continuous neighbour wave around the pointer', () => {
    expect(documentMinimapTickWidth(0, 8, 24)).toBe(24)
    expect(documentMinimapTickWidth(15, 8, 24)).toBeGreaterThan(8)
    expect(documentMinimapTickWidth(15, 8, 24)).toBeLessThan(24)
    expect(documentMinimapTickWidth(100, 8, 24)).toBeCloseTo(8, 2)
  })
})
