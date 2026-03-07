<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
import { userApi } from '../services/api/user'

const props = defineProps<{
  mode: 'create' | 'edit'
  task?: Task | null
}>()

const emit = defineEmits<{
  close: []
}>()

const store = useTaskStore()

const formTitle = ref('')
const formDescription = ref('')
const formStatus = ref<Status>('todo')
const formPriority = ref<Priority>('medium')
const formAssigneeId = ref<string | number>('')
const formDueDate = ref('') // YYYY-MM-DD for input[type=date]
const isSaving = ref(false)
const userList = ref<User[]>([])

onMounted(async () => {
  try {
    userList.value = await userApi.list()
  } catch (e) {
    console.error('Failed to load users:', e)
  }
})

function toDateInputValue(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return d.toISOString().slice(0, 10)
}

const loadForm = () => {
  if (props.mode === 'edit' && props.task) {
    formTitle.value = props.task.title
    formDescription.value = props.task.description || ''
    formStatus.value = props.task.status
    formPriority.value = props.task.priority
    formAssigneeId.value = props.task.assigneeId ?? ''
    formDueDate.value = toDateInputValue(props.task.dueDate ?? undefined)
  } else {
    formTitle.value = ''
    formDescription.value = ''
    formStatus.value = 'todo'
    formPriority.value = 'medium'
    formAssigneeId.value = ''
    formDueDate.value = ''
  }
}

watch(() => props.task, loadForm, { immediate: true })
watch(() => props.mode, loadForm)

const handleSave = async () => {
  if (!formTitle.value.trim()) return

  isSaving.value = true
  const dueDateMs =
    formDueDate.value
      ? new Date(formDueDate.value + 'T00:00:00').getTime()
      : undefined

  try {
    if (props.mode === 'create') {
      await store.createTask({
        title: formTitle.value.trim(),
        description: formDescription.value.trim() || undefined,
        status: formStatus.value,
        priority: formPriority.value,
        assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
        dueDate: dueDateMs
      })
    } else if (props.mode === 'edit' && props.task) {
      await store.updateTask(props.task.id, {
        title: formTitle.value.trim(),
        description: formDescription.value.trim() || undefined,
        status: formStatus.value,
        priority: formPriority.value,
        assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
        dueDate: dueDateMs
      })
    }
    closeEditor()
  } catch (error) {
    console.error('Failed to save task:', error)
  } finally {
    isSaving.value = false
  }
}

const closeEditor = () => {
  emit('close')
}
</script>

<template>
  <div class="editor-overlay" @click.self="closeEditor">
    <div class="editor-drawer">
      <div class="editor-header">
        <h2>{{ mode === 'create' ? 'New Task' : 'Edit Task' }}</h2>
        <button class="close-btn" @click="closeEditor">×</button>
      </div>
      
      <div class="editor-body">
        <input 
          v-model="formTitle" 
          class="title-input" 
          placeholder="Task title" 
          autofocus 
          @keydown.enter="handleSave"
        />
        
        <textarea 
          v-model="formDescription" 
          class="description-input" 
          placeholder="Add description..."
          rows="5"
        ></textarea>
        
        <div class="form-row">
          <div class="form-group">
            <label for="task-status">Status</label>
            <select id="task-status" v-model="formStatus" class="select-input">
              <option value="todo">Todo</option>
              <option value="in_progress">In Progress</option>
              <option value="done">Done</option>
            </select>
          </div>
          
          <div class="form-group">
            <label for="task-priority">Priority</label>
            <select id="task-priority" v-model="formPriority" class="select-input">
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
              <option value="urgent">Urgent</option>
            </select>
          </div>
        </div>
        <div class="form-group">
          <label for="task-assignee">Assignee</label>
          <select id="task-assignee" v-model="formAssigneeId" class="select-input">
            <option value="">Unassigned</option>
            <option v-for="u in userList" :key="u.id" :value="u.id">{{ u.username }}</option>
          </select>
        </div>
        <div class="form-group">
          <label for="task-due">Due Date</label>
          <input
            id="task-due"
            v-model="formDueDate"
            type="date"
            class="select-input"
          />
        </div>
        <div v-if="mode === 'edit' && task?.completedAt" class="form-group read-only">
          <label>Completed at</label>
          <span class="read-only-value">{{ new Date(task.completedAt).toLocaleString() }}</span>
        </div>
      </div>
      
      <div class="editor-footer">
        <button class="btn-cancel" @click="closeEditor">Cancel</button>
        <button 
          class="btn-save" 
          :disabled="!formTitle.trim() || isSaving" 
          @click="handleSave"
        >
          {{ isSaving ? 'Saving...' : 'Save' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-overlay {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 100;
  display: flex;
  justify-content: flex-end;
}
.editor-drawer {
  width: 400px;
  background: var(--color-bg-primary);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 15px rgba(0,0,0,0.3);
  animation: slideIn var(--transition-normal);
}
@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
.editor-header {
  height: var(--header-height);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
}
.editor-header h2 {
  font-size: 16px;
  font-weight: 500;
  margin: 0;
}
.close-btn {
  font-size: 24px;
  line-height: 1;
  color: var(--color-text-secondary);
}
.close-btn:hover { color: var(--color-text-primary); }
.editor-body {
  flex: 1;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}
.title-input {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-primary);
}
.title-input::placeholder { color: var(--color-text-secondary); }
.description-input {
  font-size: 14px;
  color: var(--color-text-primary);
  resize: vertical;
  line-height: 1.5;
}
.form-row {
  display: flex;
  gap: 16px;
}
.form-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.form-group label {
  font-size: 12px;
  color: var(--color-text-secondary);
  font-weight: 500;
}
.select-input {
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 8px;
  border-radius: var(--border-radius-sm);
  outline: none;
}
.read-only .read-only-value {
  font-size: 14px;
  color: var(--color-text-secondary);
}
.editor-footer {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.btn-cancel {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
}
.btn-cancel:hover { background: var(--color-bg-hover); }
.btn-save {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  background: var(--color-accent);
  color: white;
  font-weight: 500;
}
.btn-save:hover:not(:disabled) { background: var(--color-accent-hover); }
.btn-save:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
