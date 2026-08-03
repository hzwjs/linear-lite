<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  buildDocumentMinimapSegments,
  closestDocumentMinimapSegmentIndex,
  closestDocumentMinimapTickIndex,
  documentKeyboardScrollTop,
  documentMinimapTickPositions,
  documentMinimapTickWidth,
  documentScrollProgress,
  type DocumentMinimapBlock,
  type DocumentMinimapSegment
} from './documentMinimap'

const props = defineProps<{
  scrollElement: HTMLElement | null
  controls: string
}>()

const { t } = useI18n()
const rootRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const available = ref(false)
const progress = ref(0)
const previewOpen = ref(false)
const previewTop = ref(0)
const previewTitle = ref('')
const previewLines = ref<string[]>([])
const dragging = ref(false)

let segments: DocumentMinimapSegment[] = []
let observedScroller: HTMLElement | null = null
let observedBody: HTMLElement | null = null
let resizeObserver: ResizeObserver | null = null
let mutationObserver: MutationObserver | null = null
let layoutFrame = 0
let drawFrame = 0
let hoverPointerY: number | null = null
let palette = {
  inactive: '#8a8a8a',
  active: '#202020'
}

const ariaValueText = computed(() => t('documents.minimapPosition', { percent: progress.value }))

function normalizedText(element: Element): string {
  if (element instanceof HTMLInputElement) return element.value.replace(/\s+/g, ' ').trim()
  const text = element.textContent
  if (text == null) return ''
  return text.replace(/\s+/g, ' ').trim()
}

function queueDraw() {
  if (drawFrame !== 0) return
  drawFrame = window.requestAnimationFrame(() => {
    drawFrame = 0
    draw()
  })
}

function queueLayout() {
  if (layoutFrame !== 0) return
  layoutFrame = window.requestAnimationFrame(() => {
    layoutFrame = 0
    measureSegments()
    draw()
  })
}

function measureSegments() {
  const scroller = props.scrollElement
  if (scroller == null) {
    segments = []
    available.value = false
    return
  }

  available.value = scroller.scrollHeight > scroller.clientHeight * 1.2
  const scrollerRect = scroller.getBoundingClientRect()
  const title = scroller.querySelector<HTMLElement>('.document-editor__title')
  const body = scroller.querySelector<HTMLElement>('.document-editor__body')
  if (body != null && body !== observedBody) {
    observedBody = body
    resizeObserver?.observe(body)
  }
  // 缩略图只读取 BlockNote 的固定块内容节点，禁止从其它 DOM 层级推断或回退正文数据。
  const blockContents = Array.from(
    scroller.querySelectorAll<HTMLElement>('.bn-block-outer[data-id] .bn-block-content')
  )
  const contentRects = blockContents.map((content) => content.getBoundingClientRect())
  const contentLeft = contentRects.length > 0 ? Math.min(...contentRects.map((rect) => rect.left)) : scrollerRect.left
  const contentWidth = Math.max(1, scroller.clientWidth - Math.max(0, contentLeft - scrollerRect.left) * 2)

  const measured: DocumentMinimapBlock[] = []
  if (title != null) {
    const rect = title.getBoundingClientRect()
    measured.push({
      top: rect.top - scrollerRect.top + scroller.scrollTop,
      height: rect.height,
      indent: 0,
      emphasized: true,
      text: normalizedText(title)
    })
  }

  blockContents.forEach((content, index) => {
    const rect = contentRects[index]
    if (rect == null) return
    const text = normalizedText(content)
    const contentType = content.dataset.contentType
    if (contentType == null) return
    const emphasized = contentType.startsWith('heading')
    const indent = Math.min(0.34, Math.max(0, (rect.left - contentLeft) / contentWidth))
    measured.push({
      top: rect.top - scrollerRect.top + scroller.scrollTop,
      height: rect.height,
      indent,
      emphasized,
      text
    })
  })
  segments = buildDocumentMinimapSegments(measured, scroller.scrollHeight)
  palette = {
    inactive: cssColor('--color-text-muted'),
    active: cssColor('--color-text-primary')
  }
}

function canvasMetrics() {
  const canvas = canvasRef.value
  if (canvas == null) return null
  const rect = canvas.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return null
  const pixelRatio = window.devicePixelRatio
  const targetWidth = Math.round(rect.width * pixelRatio)
  const targetHeight = Math.round(rect.height * pixelRatio)
  if (canvas.width !== targetWidth || canvas.height !== targetHeight) {
    canvas.width = targetWidth
    canvas.height = targetHeight
  }
  const context = canvas.getContext('2d')
  if (context == null) return null
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)
  return { context, width: rect.width, height: rect.height }
}

function cssColor(name: string): string {
  const root = rootRef.value
  if (root == null) return ''
  return window.getComputedStyle(root).getPropertyValue(name).trim()
}

function draw() {
  const scroller = props.scrollElement
  const metrics = canvasMetrics()
  if (scroller == null || metrics == null) return
  const { context, width, height } = metrics
  context.clearRect(0, 0, width, height)
  if (!available.value) return

  const tickPositions = documentMinimapTickPositions(segments, height)
  const readingY = scroller.scrollTop + Math.min(scroller.clientHeight * 0.28, 180)
  const activeIndex = closestDocumentMinimapSegmentIndex(segments, readingY)
  const hoverIndex = hoverPointerY == null
    ? -1
    : closestDocumentMinimapTickIndex(tickPositions, hoverPointerY)
  context.lineCap = 'round'
  segments.forEach((_, index) => {
    const y = tickPositions[index]
    if (y == null) return
    const lineHeight = 2
    const tickWidth = hoverPointerY == null
      ? index === activeIndex ? 16 : 8
      : documentMinimapTickWidth(Math.abs(y - hoverPointerY), 8, width)
    const highlighted = hoverPointerY == null ? index === activeIndex : index === hoverIndex
    // 静止刻度保持同色同长；交互时只由指针距离生成连续波峰，消除斑马线感。
    context.fillStyle = highlighted ? palette.active : palette.inactive
    context.fillRect(0, y, tickWidth, lineHeight)
  })
  progress.value = documentScrollProgress(scroller.scrollTop, scroller.scrollHeight, scroller.clientHeight)
}

function segmentIndexAtPointer(pointerY: number): number {
  const scroller = props.scrollElement
  const canvas = canvasRef.value
  if (scroller == null || canvas == null || segments.length === 0) return -1
  const railHeight = canvas.getBoundingClientRect().height
  if (railHeight <= 0) return -1
  const tickPositions = documentMinimapTickPositions(segments, railHeight)
  return closestDocumentMinimapTickIndex(tickPositions, pointerY)
}

function updatePreview(pointerY: number) {
  const canvas = canvasRef.value
  if (canvas == null) return
  const nearest = segments[segmentIndexAtPointer(pointerY)]
  if (nearest == null) return
  previewTitle.value = nearest.texts[0] ?? t('documents.minimapEmptyBlock')
  previewLines.value = nearest.texts.slice(1, 4)
  const railHeight = canvas.getBoundingClientRect().height
  previewTop.value = Math.min(Math.max(24, pointerY), railHeight - 24)
  previewOpen.value = true
}

function scrollFromPointer(event: PointerEvent) {
  const scroller = props.scrollElement
  const canvas = canvasRef.value
  if (scroller == null || canvas == null) return
  const rect = canvas.getBoundingClientRect()
  const segment = segments[segmentIndexAtPointer(event.clientY - rect.top)]
  if (segment == null) return
  // 点击与拖动均吸附到同一语义片段起点，保持浏览定位和预览内容一致。
  scroller.scrollTop = Math.min(segment.top, Math.max(0, scroller.scrollHeight - scroller.clientHeight))
}

function handlePointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  const canvas = canvasRef.value
  if (canvas == null) return
  event.preventDefault()
  dragging.value = true
  canvas.setPointerCapture(event.pointerId)
  hoverPointerY = event.clientY - canvas.getBoundingClientRect().top
  queueDraw()
  updatePreview(hoverPointerY)
  scrollFromPointer(event)
}

function handlePointerMove(event: PointerEvent) {
  const canvas = canvasRef.value
  if (canvas == null) return
  hoverPointerY = event.clientY - canvas.getBoundingClientRect().top
  queueDraw()
  updatePreview(hoverPointerY)
  if (dragging.value) scrollFromPointer(event)
}

function handlePointerUp(event: PointerEvent) {
  const canvas = canvasRef.value
  dragging.value = false
  if (canvas?.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId)
}

function handlePointerLeave() {
  if (!dragging.value) {
    hoverPointerY = null
    previewOpen.value = false
    queueDraw()
  }
}

function handleKeydown(event: KeyboardEvent) {
  const scroller = props.scrollElement
  if (scroller == null) return
  const target = documentKeyboardScrollTop(
    event.key,
    scroller.scrollTop,
    scroller.scrollHeight,
    scroller.clientHeight
  )
  if (target == null) return
  event.preventDefault()
  scroller.scrollTop = target
}

function teardown() {
  hoverPointerY = null
  observedScroller?.removeEventListener('scroll', queueDraw)
  window.removeEventListener('resize', queueLayout)
  resizeObserver?.disconnect()
  mutationObserver?.disconnect()
  resizeObserver = null
  mutationObserver = null
  observedScroller = null
  observedBody = null
  if (layoutFrame !== 0) window.cancelAnimationFrame(layoutFrame)
  if (drawFrame !== 0) window.cancelAnimationFrame(drawFrame)
  layoutFrame = 0
  drawFrame = 0
}

async function setup(scroller: HTMLElement | null) {
  teardown()
  if (scroller == null) return
  await nextTick()
  observedScroller = scroller
  scroller.addEventListener('scroll', queueDraw, { passive: true })
  window.addEventListener('resize', queueLayout, { passive: true })
  // JSDOM 及旧浏览器可能没有 ResizeObserver；内容变更仍由 MutationObserver 驱动布局刷新。
  if (typeof window.ResizeObserver === 'function') {
    resizeObserver = new window.ResizeObserver(queueLayout)
    resizeObserver.observe(scroller)
    if (rootRef.value != null) resizeObserver.observe(rootRef.value)
  }
  mutationObserver = new MutationObserver(queueLayout)
  mutationObserver.observe(scroller, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['data-id', 'data-content-type']
  })
  queueLayout()
}

watch(() => props.scrollElement, setup, { immediate: true })
onBeforeUnmount(teardown)
</script>

<template>
  <aside
    v-show="available"
    ref="rootRef"
    class="document-minimap"
    :class="{ 'document-minimap--dragging': dragging }"
    :aria-label="t('documents.minimapLabel')"
  >
    <canvas
      ref="canvasRef"
      class="document-minimap__canvas"
      role="scrollbar"
      tabindex="0"
      aria-orientation="vertical"
      :aria-controls="controls"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="progress"
      :aria-valuetext="ariaValueText"
      :aria-label="t('documents.minimapControlLabel')"
      @keydown="handleKeydown"
      @pointerdown="handlePointerDown"
      @pointermove="handlePointerMove"
      @pointerup="handlePointerUp"
      @pointercancel="handlePointerUp"
      @pointerleave="handlePointerLeave"
    />
    <div
      v-if="previewOpen"
      class="document-minimap__preview"
      :style="{ top: `${previewTop}px` }"
      aria-hidden="true"
    >
      <strong>{{ previewTitle }}</strong>
      <p v-for="(line, index) in previewLines" :key="index">{{ line }}</p>
    </div>
  </aside>
</template>

<style scoped>
.document-minimap {
  --document-minimap-width: 24px;
  --document-minimap-height: 140px;
  --document-minimap-preview-width: min(560px, calc(100cqw - 180px));
  position: absolute;
  z-index: 4;
  top: calc(50% + 24px);
  left: calc(var(--document-page-horizontal-inset) / 2 - var(--document-minimap-width) / 2);
  display: none;
  width: var(--document-minimap-width);
  height: var(--document-minimap-height);
  transform: translateY(-50%);
}

.document-minimap__canvas {
  display: block;
  width: 100%;
  height: 100%;
  cursor: pointer;
  touch-action: none;
}

.document-minimap--dragging .document-minimap__canvas { cursor: grabbing; }
.document-minimap__canvas:focus-visible {
  outline: 2px solid var(--color-border-strong);
  outline-offset: 2px;
  border-radius: var(--radius-sm);
}

.document-minimap__preview {
  position: absolute;
  left: calc(100% + 16px);
  width: var(--document-minimap-preview-width);
  padding: 16px 18px;
  transform: translateY(-50%);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-base);
  box-shadow: var(--shadow-popover);
  color: var(--color-text-primary);
  pointer-events: none;
}

.document-minimap__preview strong,
.document-minimap__preview p {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
}

.document-minimap__preview strong {
  -webkit-line-clamp: 2;
  font-size: var(--font-size-subhead);
  font-weight: var(--font-weight-semibold);
  line-height: 1.45;
}

.document-minimap__preview p {
  margin: 8px 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-body);
  line-height: 1.55;
  -webkit-line-clamp: 2;
}

@container document-editor (min-width: 920px) {
  .document-minimap { display: block; }
}

</style>
