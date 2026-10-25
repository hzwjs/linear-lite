<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AssigneeOption, Task } from '../types/domain'
import AssigneeSelect from './ui/AssigneeSelect.vue'
import { useAssigneeStore } from '../store/assigneeStore'

const props = defineProps<{
  taskId: string
  task: Task
  tooltip?: string
}>()

const emit = defineEmits<{
  pick: [assigneeKey: string | null, options: { clearAssignee: boolean }]
}>()

const { t } = useI18n()
const assigneeStore = useAssigneeStore()
const assignees = computed<AssigneeOption[]>(() => {
  const projectId = props.task.projectId
  return projectId == null ? [] : assigneeStore.optionsByProject[projectId] ?? []
})
const value = computed(() => props.task.assigneeKey ?? '')

function pick(value: string) {
  const assigneeKey = value === '' ? null : value
  if (assigneeKey === (props.task.assigneeKey ?? null)) return
  emit('pick', assigneeKey, { clearAssignee: assigneeKey == null })
}
</script>

<template>
  <AssigneeSelect
    :model-value="value"
    :assignees="assignees"
    :selected-name="task.assigneeKey != null && task.assigneeName != null ? { key: task.assigneeKey, name: task.assigneeName } : undefined"
    :placeholder="t('common.unassigned')"
    :aria-label="t('taskList.changeAssignee')"
    :tooltip="tooltip"
    trigger-mode="avatar"
    @click.stop
    @update:model-value="pick"
  />
</template>
