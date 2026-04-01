<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Calendar, CalendarClock } from 'lucide-vue-next'
import CustomDatePicker from './ui/CustomDatePicker.vue'
import { formatDateInputValue, parseDateInputValue } from '../utils/taskDate'

const props = defineProps<{
  taskId: string
  /** 毫秒时间戳，空为未设置 */
  dateMs: number | null | undefined
  variant: 'planned' | 'due'
  /** 仅 variant=due 时用于逾期样式 */
  overdue?: boolean
}>()

const emit = defineEmits<{
  commit: [value: number | null]
}>()

const { t, locale } = useI18n()

const pickerRef = ref<InstanceType<typeof CustomDatePicker> | null>(null)
const draft = ref(formatDateInputValue(props.dateMs ?? null))

watch(
  () => props.dateMs,
  (ms) => {
    draft.value = formatDateInputValue(ms ?? null)
  }
)

const panelId = computed(() => {
  const safe = props.taskId.replace(/[^a-zA-Z0-9_-]/g, '-')
  return `date-panel-${props.variant}-${safe}`
})

function displayShort(ms: number | null | undefined): string {
  if (ms == null) return '—'
  return new Date(ms).toLocaleDateString(locale.value, { month: 'short', day: 'numeric' })
}

const labelText = computed(() => displayShort(props.dateMs ?? null))

const columnTitle = computed(() =>
  props.variant === 'planned' ? t('taskList.columnPlannedStart') : t('taskList.columnDueDate')
)

const hasDate = computed(() => props.dateMs != null)

function onModelUpdate(v: string) {
  draft.value = v
  const ms = parseDateInputValue(v)
  if (ms === undefined) return
  emit('commit', ms)
  pickerRef.value?.close()
}

function onClear() {
  emit('commit', null)
  pickerRef.value?.close()
}
</script>

<template>
  <span class="task-row-inline-date-wrap">
    <CustomDatePicker
      ref="pickerRef"
      v-model="draft"
      :panel-dom-id="panelId"
      @update:model-value="onModelUpdate"
    >
      <template #trigger="{ toggle, isOpen }">
        <button
          type="button"
          class="task-row-inline-date-trigger"
          :class="{ overdue: variant === 'due' && overdue }"
          :title="columnTitle"
          :aria-label="columnTitle"
          :aria-expanded="isOpen"
          aria-haspopup="dialog"
          @click.stop="toggle"
        >
          <CalendarClock v-if="variant === 'planned'" class="task-meta-date-icon" stroke-width="2" aria-hidden="true" />
          <Calendar v-else class="task-meta-date-icon" stroke-width="2" aria-hidden="true" />
          <span>{{ labelText }}</span>
        </button>
      </template>
      <template v-if="hasDate" #panel-footer>
        <button type="button" class="task-row-date-clear-btn" @click.stop="onClear">
          {{ t('taskList.clearDate') }}
        </button>
      </template>
    </CustomDatePicker>
  </span>
</template>

<style scoped>
.task-row-inline-date-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
  max-width: 100%;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}
.task-row-inline-date-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 0;
  margin: 0;
  font: inherit;
  color: inherit;
  background: none;
  border: none;
  cursor: pointer;
  border-radius: var(--border-radius-sm, 4px);
  text-align: left;
}
.task-row-inline-date-trigger:hover {
  color: var(--color-text-primary);
}
.task-row-inline-date-trigger.overdue {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}
.task-row-inline-date-trigger.overdue .task-meta-date-icon {
  color: var(--color-status-warning);
  opacity: 1;
}
.task-row-inline-date-trigger:focus-visible {
  outline: 2px solid var(--color-status-done);
  outline-offset: 2px;
}
.task-meta-date-icon {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  color: var(--color-text-secondary);
}
.task-row-date-clear-btn {
  width: 100%;
  padding: 6px 10px;
  font-size: var(--font-size-caption, 12px);
  color: var(--color-text-secondary);
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  cursor: pointer;
}
.task-row-date-clear-btn:hover {
  background: var(--color-bg-hover, #f3f4f6);
  color: var(--color-text-primary);
}
</style>
