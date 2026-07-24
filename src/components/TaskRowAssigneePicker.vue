<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Task, User } from '../types/domain'
import AssigneeSelect from './ui/AssigneeSelect.vue'

const props = defineProps<{
  taskId: string
  task: Task
  users?: User[]
  tooltip?: string
}>()

const emit = defineEmits<{
  pick: [userId: number | null, options: { clearAssignee: boolean }]
}>()

const { t } = useI18n()
const value = computed(() => props.task.assigneeId ?? '')
const externalLabel = computed(() =>
  props.task.assigneeId == null ? props.task.assigneeDisplayName?.trim() ?? '' : ''
)

function pick(value: string | number) {
  const userId = value === '' ? null : Number(value)
  const currentId = props.task.assigneeId ?? null
  const hasImportedAssignee = currentId == null && !!externalLabel.value
  if (userId === currentId && !hasImportedAssignee) return
  emit('pick', userId, { clearAssignee: userId == null })
}
</script>

<template>
  <AssigneeSelect
    :model-value="value"
    :users="users ?? []"
    :external-label="externalLabel"
    :placeholder="t('common.unassigned')"
    :aria-label="t('taskList.changeAssignee')"
    :tooltip="tooltip"
    trigger-mode="avatar"
    @update:model-value="pick"
  />
</template>
