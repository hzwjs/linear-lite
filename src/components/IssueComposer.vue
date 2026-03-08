<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useTaskStore } from '../store/taskStore'
import type { Priority, Status, User } from '../types/domain'
import { userApi } from '../services/api/user'
import CustomSelect from './ui/CustomSelect.vue'
import CustomDatePicker from './ui/CustomDatePicker.vue'
import type { CustomSelectOption } from './ui/CustomSelect.vue'
import {
  ArrowDown,
  ArrowUp,
  CheckCircle,
  Circle,
  Flame,
  Loader2,
  Minus,
  User as UserIcon
} from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  defaultStatus?: Status
}>()

const emit = defineEmits<{
  close: []
  created: [taskId: string]
}>()

const store = useTaskStore()

const title = ref('')
const description = ref('')
const status = ref<Status>('todo')
const priority = ref<Priority>('medium')
const assigneeId = ref<string | number>('')
const dueDate = ref('')
const createMore = ref(false)
const isSaving = ref(false)
const userList = ref<User[]>([])

const statusOptions: CustomSelectOption[] = [
  { value: 'todo', label: 'Todo', icon: Circle },
  { value: 'in_progress', label: 'In Progress', icon: Loader2 },
  { value: 'done', label: 'Done', icon: CheckCircle }
]
const priorityOptions: CustomSelectOption[] = [
  { value: 'low', label: 'Low', icon: ArrowDown },
  { value: 'medium', label: 'Medium', icon: Minus },
  { value: 'high', label: 'High', icon: ArrowUp },
  { value: 'urgent', label: 'Urgent', icon: Flame }
]

const assigneeOptions = computed<CustomSelectOption[]>(() => {
  const list: CustomSelectOption[] = [{ value: '', label: 'Unassigned', icon: UserIcon }]
  for (const user of userList.value) {
    list.push({ value: user.id, label: user.username, icon: UserIcon })
  }
  return list
})

function resetForm() {
  title.value = ''
  description.value = ''
  status.value = props.defaultStatus ?? 'todo'
  priority.value = 'medium'
  assigneeId.value = ''
  dueDate.value = ''
}

watch(
  () => props.open,
  (open) => {
    if (open) resetForm()
  },
  { immediate: true }
)

watch(
  () => props.defaultStatus,
  (value) => {
    if (props.open) status.value = value ?? 'todo'
  }
)

onMounted(async () => {
  try {
    userList.value = await userApi.list()
  } catch (error) {
    console.error('Failed to load users:', error)
  }
})

async function handleCreate() {
  if (!title.value.trim() || isSaving.value) return

  isSaving.value = true
  const dueDateMs = dueDate.value ? new Date(dueDate.value + 'T00:00:00').getTime() : undefined

  try {
    const task = await store.createTask({
      title: title.value.trim(),
      description: description.value.trim() || undefined,
      status: status.value,
      priority: priority.value,
      assigneeId: assigneeId.value === '' ? null : Number(assigneeId.value),
      dueDate: dueDateMs
    })

    if (createMore.value) {
      resetForm()
    } else {
      emit('created', task.id)
      emit('close')
    }
  } catch (error) {
    console.error('Failed to create task:', error)
  } finally {
    isSaving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="composer-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Create issue"
      @click.self="emit('close')"
    >
      <div class="composer-panel">
        <div class="composer-header">
          <div class="composer-title">New issue</div>
          <button class="composer-close" type="button" aria-label="Close" @click="emit('close')">
            ×
          </button>
        </div>

        <div class="composer-body">
          <textarea
            v-model="title"
            class="composer-input composer-title-input"
            placeholder="Issue title"
            rows="2"
            autofocus
          />
          <textarea
            v-model="description"
            class="composer-input composer-description-input"
            placeholder="Add description..."
            rows="3"
          />

          <div class="composer-props">
            <CustomSelect
              id="composer-status"
              v-model="status"
              :options="statusOptions"
              aria-label="Status"
              trigger-class="composer-trigger"
            />
            <CustomSelect
              id="composer-priority"
              v-model="priority"
              :options="priorityOptions"
              aria-label="Priority"
              trigger-class="composer-trigger"
            />
            <CustomSelect
              id="composer-assignee"
              v-model="assigneeId"
              :options="assigneeOptions"
              placeholder="Assignee"
              aria-label="Assignee"
              trigger-class="composer-trigger"
            />
            <CustomDatePicker
              id="composer-due-date"
              v-model="dueDate"
              placeholder="Due date"
              aria-label="Due date"
              trigger-class="composer-trigger"
            />
          </div>
        </div>

        <div class="composer-footer">
          <label class="composer-more">
            <input v-model="createMore" type="checkbox" />
            <span>Create more</span>
          </label>
          <button
            class="composer-submit"
            type="button"
            :disabled="!title.trim() || isSaving"
            @click="handleCreate"
          >
            {{ isSaving ? 'Creating...' : 'Create issue' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.composer-overlay {
  position: fixed;
  inset: 0;
  z-index: 220;
  background: rgba(17, 24, 39, 0.08);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 24px 24px;
}

.composer-panel {
  width: min(720px, 100%);
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  box-shadow: var(--shadow-popover);
}

.composer-header,
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
}

.composer-header {
  border-bottom: 1px solid var(--color-border);
}

.composer-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.composer-close {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 6px;
  color: var(--color-text-secondary);
}

.composer-close:hover {
  background: var(--color-hover);
  color: var(--color-text-primary);
}

.composer-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
}

.composer-input {
  width: 100%;
  resize: none;
  border: none;
  padding: 0;
  background: transparent;
}

.composer-title-input {
  min-height: 52px;
  font-size: 24px;
  line-height: 1.2;
  font-weight: 650;
  color: var(--color-text-primary);
}

.composer-description-input {
  min-height: 72px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.composer-props {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

:deep(.composer-trigger) {
  min-width: 122px;
  background: var(--color-hover);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  min-height: 34px;
}

.composer-footer {
  border-top: 1px solid var(--color-border);
}

.composer-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.composer-submit {
  background: var(--color-accent);
  color: #fff;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
}

.composer-submit:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

@media (max-width: 720px) {
  .composer-overlay {
    padding: 16px;
    align-items: stretch;
  }

  .composer-panel {
    width: 100%;
  }

  .composer-title-input {
    font-size: 20px;
  }
}
</style>
