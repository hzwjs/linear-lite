<script setup lang="ts">
import { Circle, CircleDashed, CircleX, Clock3, Copy } from 'lucide-vue-next'
import type { Status } from '../types/domain'

withDefaults(defineProps<{ status: Status; size?: number }>(), { size: 16 })

const outlineIcons = {
  backlog: CircleDashed,
  todo: Circle,
  in_review: Clock3,
  canceled: CircleX,
  duplicate: Copy
}
const strokeWidth = 3
</script>

<template>
  <svg
    v-if="status === 'in_progress' || status === 'done'"
    class="task-status-icon"
    :class="`task-status-icon--${status}`"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 半填充表示执行进度，实心勾表示完成；形状承担颜色之外的状态识别。 -->
    <circle cx="12" cy="12" r="9" :fill="status === 'done' ? 'currentColor' : 'none'" />
    <path v-if="status === 'in_progress'" d="M12 3a9 9 0 0 1 0 18Z" fill="currentColor" stroke="none" />
    <path v-else d="m8 12 2.5 2.5 5.5-5.5" class="task-status-icon-check" />
  </svg>
  <component
    :is="outlineIcons[status]"
    v-else
    class="task-status-icon"
    :class="`task-status-icon--${status}`"
    :size="size"
    :stroke-width="strokeWidth"
    aria-hidden="true"
  />
</template>

<style scoped>
.task-status-icon { flex-shrink: 0; vertical-align: middle; }
.task-status-icon--todo { color: var(--color-status-todo); }
.task-status-icon--in_progress { color: var(--color-status-in-progress); }
.task-status-icon--in_review { color: var(--color-status-in-review); }
.task-status-icon--done { color: var(--color-success); }
.task-status-icon--backlog,
.task-status-icon--canceled { color: var(--color-text-muted); }
.task-status-icon--duplicate { color: var(--color-status-duplicate); }
.task-status-icon-check { stroke: var(--color-text-on-accent); }
</style>
