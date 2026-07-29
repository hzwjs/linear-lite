<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
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
  yearRange: [year: number]
  shift: [direction: -1 | 1]
}>()

const granularities: Granularity[] = ['day', 'week', 'month', 'year']
const selectedYear = computed(() => Number(props.from.slice(0, 4)))

function updateYear(event: Event) {
  const year = Number((event.target as HTMLInputElement).value)
  if (Number.isInteger(year) && year >= 1970 && year <= 2100) emit('yearRange', year)
}
</script>

<template>
  <div class="analytics-filters" :aria-label="t('analytics.filterLabel')">
    <div class="granularity-group" role="group" :aria-label="t('analytics.granularityLabel')">
      <button
        v-for="item in granularities"
        :key="item"
        type="button"
        class="granularity-button"
        :class="{ 'granularity-button--active': granularity === item }"
        :aria-pressed="granularity === item"
        :data-testid="`granularity-${item}`"
        @click="emit('update:granularity', item)"
      >
        {{ t(`analytics.granularity.${item}`) }}
      </button>
    </div>

    <div class="period-control">
      <button
        type="button"
        class="period-step"
        :aria-label="t('analytics.previousPeriod')"
        @click="emit('shift', -1)"
      >
        <ChevronLeft :size="15" aria-hidden="true" />
      </button>

      <div class="date-fields">
        <input
          v-if="granularity === 'day'"
          type="date"
          class="date-input"
          :aria-label="t('analytics.dateSingle')"
          :value="from"
          @change="emit('update:from', ($event.target as HTMLInputElement).value)"
        />
        <input
          v-else-if="granularity === 'year'"
          type="number"
          class="date-input date-input--year"
          :aria-label="t('analytics.yearSingle')"
          :value="selectedYear"
          min="1970"
          max="2100"
          step="1"
          @change="updateYear"
        />
        <template v-else>
          <input
            type="date"
            class="date-input"
            :aria-label="t('analytics.rangeStart')"
            :value="from"
            @change="emit('update:from', ($event.target as HTMLInputElement).value)"
          />
          <span class="date-separator" aria-hidden="true">—</span>
          <input
            type="date"
            class="date-input"
            :aria-label="t('analytics.rangeEnd')"
            :value="to"
            @change="emit('update:to', ($event.target as HTMLInputElement).value)"
          />
        </template>
      </div>

      <button
        type="button"
        class="period-step"
        :aria-label="t('analytics.nextPeriod')"
        @click="emit('shift', 1)"
      >
        <ChevronRight :size="15" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.analytics-filters { display: flex; align-items: center; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }
.granularity-group { display: inline-flex; padding: 2px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-subtle); }
.granularity-button { min-width: 42px; min-height: 32px; padding: 5px 11px; border-radius: var(--radius-sm); color: var(--color-text-muted); font-size: 12px; font-weight: 550; transition: background-color var(--transition-fast), color var(--transition-fast); }
.granularity-button:hover { color: var(--color-text-primary); }
.granularity-button--active { color: var(--color-text-primary); background: var(--color-bg-base); box-shadow: var(--shadow-subtle); }
.granularity-button:focus-visible,
.period-step:focus-visible,
.date-input:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.period-control { display: flex; align-items: center; min-height: 38px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-base); }
.period-step { display: inline-flex; align-items: center; justify-content: center; width: 34px; min-height: 36px; padding: 0; color: var(--color-text-muted); }
.period-step:hover { color: var(--color-text-primary); background: var(--color-bg-hover); }
.date-fields { display: flex; align-items: center; min-height: 26px; padding-inline: 4px; border-inline: 1px solid var(--color-border); }
.date-input { width: 126px; min-height: 30px; padding: 3px 6px; color: var(--color-text-secondary); font-size: 12px; text-align: center; }
.date-input--year { width: 76px; }
.date-separator { color: var(--color-text-muted); font-size: var(--font-size-caption); }

@media (max-width: 720px) {
  .analytics-filters { justify-content: flex-start; }
  .period-control { max-width: 100%; }
  .date-input { width: 112px; }
}
</style>
