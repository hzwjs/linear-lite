export function clampDocumentScrollTop(value: number, scrollHeight: number, clientHeight: number): number {
  return Math.min(Math.max(0, value), Math.max(0, scrollHeight - clientHeight))
}

export type DocumentMinimapBlock = {
  top: number
  height: number
  indent: number
  emphasized: boolean
  text: string
}

export type DocumentMinimapSegment = {
  top: number
  height: number
  indent: number
  emphasized: boolean
  texts: string[]
}

const PREFERRED_SEGMENT_COUNT = 8
const MAX_SEGMENT_COUNT = 10
const MIN_SEGMENT_SPAN = 420
const MINIMAP_TICK_GAP = 15

function mergeSegments(
  first: DocumentMinimapSegment,
  second: DocumentMinimapSegment
): DocumentMinimapSegment {
  const bottom = Math.max(first.top + first.height, second.top + second.height)
  return {
    top: first.top,
    height: bottom - first.top,
    indent: Math.min(first.indent, second.indent),
    emphasized: first.emphasized || second.emphasized,
    texts: [...first.texts, ...second.texts]
  }
}

function segmentFromBlocks(blocks: DocumentMinimapBlock[]): DocumentMinimapSegment | null {
  const first = blocks[0]
  if (first == null) return null
  const bottom = blocks.reduce((maximum, block) => Math.max(maximum, block.top + block.height), first.top)
  return {
    top: first.top,
    height: bottom - first.top,
    indent: Math.min(...blocks.map((block) => block.indent)),
    emphasized: first.emphasized,
    texts: blocks.map((block) => block.text).filter((text) => text !== '')
  }
}

/**
 * 标题开启新的语义片段，连续正文再按文档高度聚合，避免逐块刻度造成视觉噪音。
 */
export function buildDocumentMinimapSegments(
  blocks: DocumentMinimapBlock[],
  scrollHeight: number
): DocumentMinimapSegment[] {
  if (blocks.length === 0 || scrollHeight <= 0) return []
  const targetSpan = Math.max(MIN_SEGMENT_SPAN, scrollHeight / PREFERRED_SEGMENT_COUNT)
  const semanticSections: DocumentMinimapBlock[][] = []
  let currentSection: DocumentMinimapBlock[] = []
  for (const block of blocks) {
    if (block.emphasized && currentSection.length > 0) {
      semanticSections.push(currentSection)
      currentSection = []
    }
    currentSection.push(block)
  }
  if (currentSection.length > 0) semanticSections.push(currentSection)

  const grouped: DocumentMinimapSegment[] = []
  for (const section of semanticSections) {
    const wholeSection = segmentFromBlocks(section)
    if (wholeSection == null) continue
    const chunkCount = Math.ceil(wholeSection.height / targetSpan)
    if (chunkCount <= 1) {
      grouped.push(wholeSection)
      continue
    }

    const chunks = Array.from({ length: chunkCount }, () => [] as DocumentMinimapBlock[])
    for (const block of section) {
      const relativeTop = Math.max(0, block.top - wholeSection.top)
      const chunkIndex = Math.min(chunkCount - 1, Math.floor((relativeTop / wholeSection.height) * chunkCount))
      chunks[chunkIndex]!.push(block)
    }
    chunks.forEach((chunk) => {
      const segment = segmentFromBlocks(chunk)
      if (segment != null) grouped.push(segment)
    })
  }

  // 极端长文档继续合并相邻片段，使导航保持可快速扫读的固定信息密度。
  while (grouped.length > MAX_SEGMENT_COUNT) {
    let mergeAt = grouped.findIndex((segment, index) => index > 0 && !segment.emphasized)
    if (mergeAt < 1) {
      let smallestGap = Number.POSITIVE_INFINITY
      for (let index = 1; index < grouped.length; index += 1) {
        const previous = grouped[index - 1]
        const segment = grouped[index]
        if (previous == null || segment == null) continue
        const gap = segment.top - (previous.top + previous.height)
        if (gap < smallestGap) {
          smallestGap = gap
          mergeAt = index
        }
      }
    }
    const first = grouped[mergeAt - 1]
    const second = grouped[mergeAt]
    if (first == null || second == null) break
    grouped.splice(mergeAt - 1, 2, mergeSegments(first, second))
  }

  return grouped
}

export function closestDocumentMinimapSegmentIndex(
  segments: DocumentMinimapSegment[],
  documentY: number
): number {
  if (segments.length === 0) return -1
  let nearestIndex = 0
  let nearestDistance = Number.POSITIVE_INFINITY
  segments.forEach((segment, index) => {
    const distance = Math.abs(segment.top + segment.height / 2 - documentY)
    if (distance < nearestDistance) {
      nearestDistance = distance
      nearestIndex = index
    }
  })
  return nearestIndex
}

/**
 * 刻度使用固定节奏并在轨道内居中；文档片段更少时保留紧凑间隔，避免被拉成稀疏长线。
 */
export function documentMinimapTickPositions(
  segments: DocumentMinimapSegment[],
  railHeight: number
): number[] {
  if (segments.length === 0 || railHeight <= 2) return []
  if (segments.length === 1) return [railHeight / 2]
  const occupiedHeight = MINIMAP_TICK_GAP * (segments.length - 1)
  const start = (railHeight - occupiedHeight) / 2
  return segments.map((_, index) => start + index * MINIMAP_TICK_GAP)
}

export function closestDocumentMinimapTickIndex(positions: number[], pointerY: number): number {
  if (positions.length === 0) return -1
  let nearestIndex = 0
  let nearestDistance = Number.POSITIVE_INFINITY
  positions.forEach((position, index) => {
    const distance = Math.abs(position - pointerY)
    if (distance < nearestDistance) {
      nearestDistance = distance
      nearestIndex = index
    }
  })
  return nearestIndex
}

/**
 * 指针附近的刻度按高斯曲线连续放大，让相邻刻度形成自然波峰而非离散跳变。
 */
export function documentMinimapTickWidth(
  distance: number,
  baseWidth: number,
  maximumWidth: number,
  influenceRadius = 22
): number {
  const radius = Math.max(1, influenceRadius)
  const influence = Math.exp(-(distance * distance) / (2 * radius * radius))
  return baseWidth + (maximumWidth - baseWidth) * influence
}

export function documentScrollTopForRailPointer(
  pointerY: number,
  railHeight: number,
  scrollHeight: number,
  clientHeight: number
): number {
  if (railHeight <= 0 || scrollHeight <= clientHeight) return 0
  const ratio = Math.min(Math.max(0, pointerY / railHeight), 1)
  return clampDocumentScrollTop(ratio * scrollHeight - clientHeight / 2, scrollHeight, clientHeight)
}

export function documentScrollProgress(scrollTop: number, scrollHeight: number, clientHeight: number): number {
  const maximum = Math.max(0, scrollHeight - clientHeight)
  if (maximum === 0) return 0
  return Math.round((clampDocumentScrollTop(scrollTop, scrollHeight, clientHeight) / maximum) * 100)
}

export function documentKeyboardScrollTop(
  key: string,
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number
): number | null {
  const lineStep = Math.max(48, clientHeight * 0.12)
  const pageStep = Math.max(48, clientHeight * 0.82)
  if (key === 'ArrowUp') return clampDocumentScrollTop(scrollTop - lineStep, scrollHeight, clientHeight)
  if (key === 'ArrowDown') return clampDocumentScrollTop(scrollTop + lineStep, scrollHeight, clientHeight)
  if (key === 'PageUp') return clampDocumentScrollTop(scrollTop - pageStep, scrollHeight, clientHeight)
  if (key === 'PageDown') return clampDocumentScrollTop(scrollTop + pageStep, scrollHeight, clientHeight)
  if (key === 'Home') return 0
  if (key === 'End') return Math.max(0, scrollHeight - clientHeight)
  return null
}
