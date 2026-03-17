<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    ariaLabel?: string
    triggerClass?: string
  }>(),
  { placeholder: '', ariaLabel: '', triggerClass: '' }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
const { t, locale } = useI18n()

const isOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const panelStyle = ref<{ top: string; left: string }>({ top: '0', left: '0' })
const viewYear = ref(new Date().getFullYear())
const viewMonth = ref(new Date().getMonth())
const resolvedPlaceholder = computed(() => props.placeholder || t('datePicker.placeholder'))
const resolvedAriaLabel = computed(() => props.ariaLabel || t('datePicker.triggerAria'))
const weekdayLabels = computed(() => [
  t('datePicker.weekdays.mon'),
  t('datePicker.weekdays.tue'),
  t('datePicker.weekdays.wed'),
  t('datePicker.weekdays.thu'),
  t('datePicker.weekdays.fri'),
  t('datePicker.weekdays.sat'),
  t('datePicker.weekdays.sun')
])
const localeTag = computed(() => locale.value)

const displayText = computed(() => {
  if (!props.modelValue) return resolvedPlaceholder.value
  const d = new Date(props.modelValue + 'T00:00:00')
  if (isNaN(d.getTime())) return resolvedPlaceholder.value
  return d.toLocaleDateString(localeTag.value, { year: 'numeric', month: 'short', day: 'numeric' })
})

const FALLBACK_PANEL_WIDTH = 240
const FALLBACK_PANEL_HEIGHT = 280

function updatePanelPosition() {
  if (!triggerRef.value || !panelRef.value || !isOpen.value) return
  const rect = triggerRef.value.getBoundingClientRect()
  const panelRect = panelRef.value.getBoundingClientRect()
  const panelWidth = panelRect.width > 0 ? panelRect.width : FALLBACK_PANEL_WIDTH
  const panelHeight = panelRect.height > 0 ? panelRect.height : FALLBACK_PANEL_HEIGHT
  const viewportW = window.innerWidth
  const viewportH = window.innerHeight
  let left = rect.left
  let top = rect.bottom + 4
  // 触发器在视口右半侧时优先向左展开，避免日历超出右边界（典型如右侧属性栏）
  const preferOpenLeft = rect.right > viewportW / 2
  if (preferOpenLeft || rect.left + panelWidth > viewportW)
    left = rect.right - panelWidth
  // 下侧会溢出且上方有空间则向上展开
  if (rect.bottom + panelHeight > viewportH && rect.top >= panelHeight)
    top = rect.top - panelHeight - 4
  // 严格限制在视口内，避免被裁或超出后无法通过滚动查看
  left = Math.max(0, Math.min(left, viewportW - panelWidth))
  top = Math.max(0, Math.min(top, viewportH - panelHeight))
  panelStyle.value = { top: `${top}px`, left: `${left}px` }
}

function open() {
  isOpen.value = true
  nextTick(() => {
    updatePanelPosition()
    // 首帧渲染后再用实际尺寸重算一次，避免初次 getBoundingClientRect 为 0
    requestAnimationFrame(() => updatePanelPosition())
  })
  if (props.modelValue) {
    const d = new Date(props.modelValue + 'T00:00:00')
    if (!isNaN(d.getTime())) {
      viewYear.value = d.getFullYear()
      viewMonth.value = d.getMonth()
    }
  }
}
function close() {
  isOpen.value = false
}
function selectDay(y: number, m: number, day: number) {
  const d = new Date(y, m, day)
  const yy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  emit('update:modelValue', `${yy}-${mm}-${dd}`)
  close()
  triggerRef.value?.focus()
}

const calendarDays = computed(() => {
  const y = viewYear.value
  const m = viewMonth.value
  const first = new Date(y, m, 1)
  const last = new Date(y, m + 1, 0)
  const startPad = (first.getDay() + 6) % 7
  const daysInMonth = last.getDate()
  const cells: { type: 'pad' | 'day'; day?: number }[] = []
  for (let i = 0; i < startPad; i++) cells.push({ type: 'pad' })
  for (let d = 1; d <= daysInMonth; d++) cells.push({ type: 'day', day: d })
  return cells
})

const monthLabel = computed(() => {
  const d = new Date(viewYear.value, viewMonth.value, 1)
  return d.toLocaleDateString(localeTag.value, { month: 'long', year: 'numeric' })
})

const today = computed(() => {
  const t = new Date()
  return { year: t.getFullYear(), month: t.getMonth(), day: t.getDate() }
})

function isToday(y: number, m: number, day: number) {
  return (
    y === today.value.year &&
    m === today.value.month &&
    day === today.value.day
  )
}

function selectToday() {
  const t = today.value
  const yy = t.year
  const mm = String(t.month + 1).padStart(2, '0')
  const dd = String(t.day).padStart(2, '0')
  emit('update:modelValue', `${yy}-${mm}-${dd}`)
  viewYear.value = yy
  viewMonth.value = t.month
  close()
  triggerRef.value?.focus()
}

function prevMonth() {
  if (viewMonth.value === 0) {
    viewMonth.value = 11
    viewYear.value--
  } else viewMonth.value--
}
function nextMonth() {
  if (viewMonth.value === 11) {
    viewMonth.value = 0
    viewYear.value++
  } else viewMonth.value++
}

function onTriggerKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    if (isOpen.value) close()
    else open()
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
  }
}

function onPanelKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    triggerRef.value?.focus()
  }
}

function handleClickOutside(e: MouseEvent) {
  const el = e.target as Node
  if (
    isOpen.value &&
    triggerRef.value &&
    !triggerRef.value.contains(el) &&
    panelRef.value &&
    !panelRef.value.contains(el)
  ) {
    close()
  }
}

let removeListeners: (() => void) | null = null
watch(isOpen, (open) => {
  if (removeListeners) {
    removeListeners()
    removeListeners = null
  }
  if (open) {
    updatePanelPosition()
    const onUpdate = () => updatePanelPosition()
    window.addEventListener('resize', onUpdate)
    window.addEventListener('scroll', onUpdate, true)
    removeListeners = () => {
      window.removeEventListener('resize', onUpdate)
      window.removeEventListener('scroll', onUpdate, true)
    }
  }
})

onMounted(() => document.addEventListener('click', handleClickOutside))
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  if (removeListeners) removeListeners()
})
</script>

<template>
  <div class="custom-date-picker">
    <button
      ref="triggerRef"
      type="button"
      class="custom-date-picker-trigger"
      :class="triggerClass"
      :aria-label="resolvedAriaLabel"
      :aria-expanded="isOpen"
      :aria-haspopup="'dialog'"
      :aria-controls="isOpen ? 'date-picker-panel' : undefined"
      @click="isOpen ? close() : open()"
      @keydown="onTriggerKeydown"
    >
      <span class="trigger-label" :class="{ placeholder: !modelValue }">{{ displayText }}</span>
      <span class="trigger-chevron" aria-hidden="true">▼</span>
    </button>
    <Teleport to="body">
      <div
        v-show="isOpen"
        id="date-picker-panel"
        ref="panelRef"
        class="custom-date-picker-panel"
        :style="panelStyle"
        role="dialog"
        aria-modal="true"
        :aria-label="t('datePicker.dialogAria')"
        tabindex="-1"
        @keydown="onPanelKeydown"
      >
        <div class="calendar-header">
        <button type="button" class="nav-btn" :aria-label="t('datePicker.previousMonth')" @click="prevMonth">
          ‹
        </button>
        <span class="calendar-month">{{ monthLabel }}</span>
        <button type="button" class="nav-btn" :aria-label="t('datePicker.nextMonth')" @click="nextMonth">›</button>
      </div>
      <div class="calendar-weekdays">
        <span v-for="w in weekdayLabels" :key="w" class="weekday">
          {{ w }}
        </span>
      </div>
      <div class="calendar-grid">
        <template v-for="(cell, i) in calendarDays" :key="i">
          <button
            v-if="cell.type === 'day' && cell.day != null"
            type="button"
            class="calendar-day"
            :class="{
              selected:
                modelValue &&
                viewYear === new Date(modelValue + 'T00:00:00').getFullYear() &&
                viewMonth === new Date(modelValue + 'T00:00:00').getMonth() &&
                cell.day === new Date(modelValue + 'T00:00:00').getDate(),
              today: isToday(viewYear, viewMonth, cell.day)
            }"
            :aria-label="isToday(viewYear, viewMonth, cell.day) ? t('datePicker.todayAria', { day: cell.day }) : String(cell.day)"
            @click="selectDay(viewYear, viewMonth, cell.day)"
          >
            {{ cell.day }}
          </button>
          <span v-else class="calendar-day pad"></span>
        </template>
      </div>
        <div class="calendar-footer">
          <button type="button" class="today-btn" @click="selectToday">{{ t('datePicker.today') }}</button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.custom-date-picker {
  position: relative;
  display: inline-block;
}
.custom-date-picker-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 140px;
  padding: 6px 10px;
  font-size: var(--font-size-body, 14px);
  color: var(--color-text-primary);
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  box-shadow: none;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}
.custom-date-picker-trigger:hover {
  background: var(--color-hover);
}
.custom-date-picker-trigger:focus {
  outline: none;
  border-color: var(--color-status-done);
}
.trigger-label.placeholder {
  color: var(--color-text-secondary);
}
.trigger-chevron {
  font-size: 10px;
  color: var(--color-text-secondary);
}
.custom-date-picker-panel {
  position: fixed;
  z-index: 1000;
  padding: 12px;
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-md);
  box-shadow: var(--shadow-popover);
  outline: none;
  min-width: 240px;
}
.calendar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.nav-btn {
  padding: 4px 8px;
  font-size: 18px;
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  border-radius: var(--border-radius-sm);
}
.nav-btn:hover {
  background: var(--color-hover);
  color: var(--color-text-primary);
}
.calendar-month {
  font-size: var(--font-size-body, 14px);
  font-weight: 500;
  color: var(--color-text-primary);
}
.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
  margin-bottom: 8px;
  font-size: var(--font-size-caption, 12px);
  color: var(--color-text-secondary);
  text-align: center;
}
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}
.calendar-day {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-body, 14px);
  color: var(--color-text-primary);
  background: transparent;
  border: none;
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
}
.calendar-day:hover {
  background: var(--color-hover);
}
.calendar-day.selected {
  background: var(--color-status-done);
  color: white;
}
.calendar-day.today:not(.selected) {
  font-weight: 600;
  box-shadow: 0 0 0 2px var(--color-accent, #6366f1);
}
.calendar-day.today.selected {
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.8);
}
.calendar-day.pad {
  cursor: default;
}
.calendar-footer {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border-subtle, #e5e7eb);
}
.today-btn {
  width: 100%;
  padding: 6px 10px;
  font-size: var(--font-size-caption, 12px);
  color: var(--color-accent, #6366f1);
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
  transition: background var(--transition-fast);
}
.today-btn:hover {
  background: var(--color-bg-hover, #f3f4f6);
}
</style>
