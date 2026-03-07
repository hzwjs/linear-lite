<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Project } from '../types/domain'
import { useProjectStore } from '../store/projectStore'

const props = defineProps<{
  open: boolean
  project: Project | null
}>()

const emit = defineEmits<{
  close: []
  updated: []
}>()

const projectStore = useProjectStore()
const name = ref('')
const identifier = ref('')
const isSubmitting = ref(false)
const error = ref('')

watch(
  () => [props.open, props.project] as const,
  ([open, project]) => {
    if (open && project) {
      name.value = project.name
      identifier.value = project.identifier
      error.value = ''
    }
  }
)

async function submit() {
  if (!props.project) return
  const n = name.value.trim()
  const id = identifier.value.trim().toUpperCase()
  if (!n || !id) {
    error.value = 'Please enter project name and identifier'
    return
  }
  if (id.length > 16) {
    error.value = 'Identifier must be at most 16 characters'
    return
  }
  isSubmitting.value = true
  error.value = ''
  try {
    await projectStore.updateProject(props.project.id, { name: n, identifier: id })
    emit('updated')
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Update failed'
  } finally {
    isSubmitting.value = false
  }
}

function close() {
  if (!isSubmitting.value) emit('close')
}
</script>

<template>
  <div v-if="open && project" class="modal-overlay" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>Project settings</h3>
        <button type="button" class="close-btn" @click="close">×</button>
      </div>
      <form class="modal-body" @submit.prevent="submit">
        <div class="form-group">
          <label>Project name</label>
          <input v-model="name" type="text" class="input" />
        </div>
        <div class="form-group">
          <label>Identifier</label>
          <input
            v-model="identifier"
            type="text"
            class="input"
            maxlength="16"
          />
        </div>
        <p v-if="error" class="error-msg">{{ error }}</p>
        <div class="modal-footer">
          <button type="button" class="btn-cancel" @click="close">Cancel</button>
          <button type="submit" class="btn-primary" :disabled="isSubmitting">
            {{ isSubmitting ? 'Saving...' : 'Save' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal {
  background: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-lg);
  min-width: 360px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}
.close-btn {
  font-size: 24px;
  line-height: 1;
  color: var(--color-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
}
.close-btn:hover {
  color: var(--color-text-primary);
}
.modal-body {
  padding: 20px;
}
.form-group {
  margin-bottom: 16px;
}
.form-group label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}
.input {
  width: 100%;
  padding: 8px 12px;
  font-size: 14px;
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
}
.error-msg {
  margin: 0 0 12px;
  font-size: 13px;
  color: #e5484d;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.btn-cancel {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  color: var(--color-text-primary);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  cursor: pointer;
}
.btn-cancel:hover {
  background: var(--color-bg-hover);
}
.btn-primary {
  padding: 8px 16px;
  border-radius: var(--border-radius-sm);
  background: var(--color-accent);
  color: white;
  border: none;
  cursor: pointer;
}
.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-hover);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
