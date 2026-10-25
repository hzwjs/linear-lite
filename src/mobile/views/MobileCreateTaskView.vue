<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronLeft, Loader2 } from 'lucide-vue-next'
import {
  PriorityHighIcon,
  PriorityLowIcon,
  PriorityMediumIcon,
  PriorityUrgentIcon
} from '../../components/icons/PriorityIcons'
import { useAuthStore } from '../../store/authStore'
import { useTaskStore } from '../../store/taskStore'
import type { AssigneeOption, Priority, Status, Task } from '../../types/domain'
import { useProjectStore } from '../../store/projectStore'
import { useAssigneeStore } from '../../store/assigneeStore'
import { userAssigneeKey } from '../../utils/assigneeKey'

const emit = defineEmits<{ close: []; created: [task: Task]; dirty: [value: boolean] }>()
const taskStore = useTaskStore()
const projectStore = useProjectStore()
const assigneeStore = useAssigneeStore()
const authStore = useAuthStore()
const title = ref('')
const description = ref('')
const status = ref<Status>('todo')
const priority = ref<Priority>('medium')
const assigneeKey = ref(authStore.currentUser?.id == null ? '' : userAssigneeKey(authStore.currentUser.id))
const dueDate = ref('')
const assignees = computed<AssigneeOption[]>(() => {
  const projectId = projectStore.activeProjectId
  return projectId == null ? [] : assigneeStore.optionsByProject[projectId] ?? []
})
const saving = ref(false)
const error = ref('')
const canCreate = computed(() => title.value.trim().length > 0 && projectStore.activeProjectId != null && !saving.value)
const initialAssigneeKey = assigneeKey.value
const isDirty = computed(() => Boolean(
  title.value.trim() || description.value.trim() || dueDate.value || status.value !== 'todo' || priority.value !== 'medium' || assigneeKey.value !== initialAssigneeKey
))
const localizedDueDate = computed(() => dueDate.value
  ? new Date(`${dueDate.value}T12:00:00`).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
  : '未设置')
const priorityIcons = { urgent: PriorityUrgentIcon, high: PriorityHighIcon, medium: PriorityMediumIcon, low: PriorityLowIcon } satisfies Record<Priority, typeof PriorityUrgentIcon>

function preventAccidentalClose(event: BeforeUnloadEvent) {
  if (!isDirty.value) return
  event.preventDefault()
}

async function createTask() {
  if (!canCreate.value) return
  saving.value = true
  error.value = ''
  try {
    const task = await taskStore.createTask({
      title: title.value.trim(),
      description: description.value.trim() || undefined,
      status: status.value,
      priority: priority.value,
      assigneeKey: assigneeKey.value || null,
      dueDate: dueDate.value ? new Date(`${dueDate.value}T23:59:59`).getTime() : undefined
    })
    emit('created', task)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '任务创建失败，请重试。'
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', preventAccidentalClose)
  if (projectStore.activeProjectId != null) {
    await assigneeStore.fetchProjectAssignees(projectStore.activeProjectId).catch(() => {})
  }
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', preventAccidentalClose))
watch(isDirty, (value) => emit('dirty', value), { immediate: true })
</script>

<template>
  <main class="mobile-fullscreen mobile-create-view">
    <header class="mobile-navigation-bar">
      <button type="button" class="mobile-nav-back" aria-label="返回" @click="emit('close')"><ChevronLeft :size="24" /></button>
      <strong>新建任务</strong>
      <button type="button" class="mobile-nav-save" :disabled="!canCreate" @click="createTask">
        <Loader2 v-if="saving" :size="18" class="mobile-spinner" />{{ saving ? '创建中' : '创建' }}
      </button>
    </header>

    <form class="mobile-form" @submit.prevent="createTask">
      <section class="mobile-form-primary">
        <label class="mobile-field-label" for="mobile-task-title">任务标题</label>
        <textarea id="mobile-task-title" v-model="title" rows="2" maxlength="160" placeholder="要完成什么？" autofocus />
        <label class="mobile-field-label" for="mobile-task-description">描述</label>
        <textarea id="mobile-task-description" v-model="description" rows="6" placeholder="补充背景、验收标准或相关信息" />
      </section>
      <section class="mobile-form-section">
        <label><span>状态</span><select v-model="status"><option value="backlog">待规划</option><option value="todo">待处理</option><option value="in_progress">进行中</option><option value="in_review">待评审</option></select></label>
        <label><span><component :is="priorityIcons[priority]" :size="17" class="mobile-priority-icon" />优先级</span><select v-model="priority"><option value="low">低</option><option value="medium">中</option><option value="high">高</option><option value="urgent">紧急</option></select></label>
        <label><span>负责人</span><select v-model="assigneeKey"><option value="">未分配</option><option v-for="assignee in assignees" :key="assignee.key" :value="assignee.key">{{ assignee.name }}{{ assignee.principalType === 'external' ? ' · 外部' : '' }}</option></select></label>
        <label><span>截止日期</span><span class="mobile-date-control"><span>{{ localizedDueDate }}</span><input v-model="dueDate" type="date" lang="zh-CN" aria-label="选择截止日期"></span></label>
      </section>
      <p v-if="error" class="mobile-form-error" role="alert">{{ error }}</p>
    </form>
  </main>
</template>
