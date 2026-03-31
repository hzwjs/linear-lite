<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Granularity } from '../../types/analytics'

const { t } = useI18n()

const props = defineProps<{
  granularity: Granularity
  from: string
  to: string
}>()

const emit = defineEmits<{
  'update:granularity': [value: Granularity]
  'update:from': [value: string]
  'update:to': [value: string]
  'yearRange': [year: number]
}>()

function onYearNumberInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  const y = parseInt(raw, 10)
  if (!Number.isFinite(y) || y < 1970 || y > 2100) return
  emit('yearRange', y)
}

const granularities: Granularity[] = ['day', 'week', 'month', 'year']

const yearFromFrom = computed(() => {
  const s = props.from?.slice(0, 4) ?? ''
  const y = parseInt(s, 10)
  return Number.isFinite(y) ? y : new Date().getFullYear()
})
</script>

<template>
  <div class="analytics-filters">
    <div class="granularity-group">
      <button
        v-for="g in granularities"
        :key="g"
        type="button"
        class="granularity-btn"
        :class="{ active: granularity === g }"
        :data-testid="`granularity-${g}`"
        @click="emit('update:granularity', g)"
      >
        {{ t(`analytics.granularity.${g}`) }}
      </button>
    </div>
    <div class="date-range-group">
      <template v-if="granularity === 'day'">
        <label class="date-label">{{ t('analytics.dateSingle') }}</label>
        <input
          type="date"
          class="date-input"
          :value="from"
          @input="emit('update:from', ($event.target as HTMLInputElement).value)"
        />
      </template>
      <template v-else-if="granularity === 'year'">
        <label class="date-label">{{ t('analytics.yearSingle') }}</label>
        <input
          type="number"
          class="date-input date-input--year"
          :value="yearFromFrom"
          min="1970"
          max="2100"
          step="1"
          @change="onYearNumberInput"
        />
      </template>
      <template v-else>
        <label class="date-label">{{ t('analytics.dateRange') }}</label>
        <input
          type="date"
          class="date-input"
          :value="from"
          @input="emit('update:from', ($event.target as HTMLInputElement).value)"
        />
        <span class="date-sep">—</span>
        <input
          type="date"
          class="date-input"
          :value="to"
          @input="emit('update:to', ($event.target as HTMLInputElement).value)"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.analytics-filters {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.granularity-group {
  display: inline-flex;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.granularity-btn {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  border: none;
  background: var(--color-bg-base);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background 150ms, color 150ms;
}
.granularity-btn:not(:last-child) {
  border-right: 1px solid var(--color-border);
}
.granularity-btn.active {
  background: var(--color-bg-elevated);
  color: var(--color-text-primary);
}
.granularity-btn:hover:not(.active) {
  background: var(--color-bg-hover);
}
.date-range-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.date-label {
  font-size: 12px;
  color: var(--color-text-muted);
  font-weight: 500;
}
.date-input {
  padding: 5px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-size: 13px;
}
.date-input--year {
  width: 5.5rem;
}
.date-sep {
  color: var(--color-text-muted);
}
</style>
