<script setup lang="ts">
import { computed } from 'vue'
import { CalendarDays, ChevronRight } from 'lucide-vue-next'
import type { Priority, Task, User } from '../../types/domain'
import {
  PriorityHighIcon,
  PriorityLowIcon,
  PriorityMediumIcon,
  PriorityUrgentIcon
} from '../../components/icons/PriorityIcons'
import { getInitials, getAvatarColorByUsername } from '../../utils/avatar'
import { getPriorityLabel } from '../../utils/enumLabels'
import { getTaskDueState } from '../../utils/taskDueState'
import MobileStatusGlyph from './MobileStatusGlyph.vue'

const props = defineProps<{ task: Task; users: User[] }>()
defineEmits<{ open: [task: Task] }>()

const priorityIcons = {
  urgent: PriorityUrgentIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon
} satisfies Record<Priority, typeof PriorityUrgentIcon>

const assignee = computed(() => props.users.find((user) => user.id === props.task.assigneeId))
const assigneeName = computed(() => assignee.value?.username || props.task.assigneeDisplayName || '未分配')
const avatarColor = computed(() => getAvatarColorByUsername(assigneeName.value))
const dueState = computed(() => getTaskDueState(props.task.dueDate))
const dueLabel = computed(() => {
  if (!props.task.dueDate) return ''
  if (dueState.value.isToday) return '今天'
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(props.task.dueDate)
})
</script>

<template>
  <button type="button" class="mobile-task-row" @click="$emit('open', task)">
    <MobileStatusGlyph :status="task.status" :size="19" />
    <span class="mobile-task-copy">
      <span class="mobile-task-title">{{ task.title }}</span>
      <span class="mobile-task-key">{{ task.id }}</span>
    </span>
    <span v-if="assigneeName !== '未分配'" class="mobile-avatar mobile-avatar--small" :style="avatarColor" :aria-label="`负责人：${assigneeName}`">
      {{ getInitials(assigneeName) }}
    </span>
    <component
      :is="priorityIcons[task.priority]"
      class="mobile-priority-icon"
      :size="16"
      :aria-label="`优先级：${getPriorityLabel(task.priority)}`"
    />
    <span v-if="dueLabel" class="mobile-task-due" :class="{ 'is-overdue': dueState.isOverdue }">
      <CalendarDays :size="14" aria-hidden="true" />{{ dueLabel }}
    </span>
    <ChevronRight :size="17" class="mobile-task-chevron" aria-hidden="true" />
  </button>
</template>
