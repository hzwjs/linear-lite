<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowUpFromLine, CalendarDays, ChevronLeft, ChevronRight, FileText, Loader2, MessageCircle, Paperclip, Pencil, Star, Trash2, UserRound } from 'lucide-vue-next'
import {
  PriorityHighIcon,
  PriorityLowIcon,
  PriorityMediumIcon,
  PriorityUrgentIcon
} from '../../components/icons/PriorityIcons'
import { attachmentsApi } from '../../services/api/attachments'
import type { TaskAttachment } from '../../services/api/types'
import { projectApi } from '../../services/api/project'
import { taskCommentsApi, type TaskCommentDto } from '../../services/api/taskComments'
import { useFavoriteStore } from '../../store/favoriteStore'
import { useProjectStore } from '../../store/projectStore'
import { useTaskStore } from '../../store/taskStore'
import type { Priority, Status, Task, User } from '../../types/domain'
import { getInitials, getAvatarColorByUsername } from '../../utils/avatar'
import { renderBody } from '../../utils/blockNoteHtml'
import { renderMarkdown } from '../../utils/markdown'
import MobileBottomSheet from '../components/MobileBottomSheet.vue'
import MobileEmptyState from '../components/MobileEmptyState.vue'
import MobileStatusGlyph from '../components/MobileStatusGlyph.vue'
import MobileTaskRow from '../components/MobileTaskRow.vue'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{ back: []; 'open-task': [task: Task] }>()
const taskStore = useTaskStore()
const favoriteStore = useFavoriteStore()
const projectStore = useProjectStore()
const loading = ref(true)
const loadError = ref('')
const users = ref<User[]>([])
const subtasks = ref<Task[]>([])
const comments = ref<TaskCommentDto[]>([])
const attachments = ref<TaskAttachment[]>([])
const title = ref('')
const description = ref('')
const commentBody = ref('')
const savingComment = ref(false)
const savingField = ref(false)
const uploading = ref(false)
const actionError = ref('')
const retryAction = ref<(() => Promise<void>) | null>(null)
const contentEditing = ref(false)
const descriptionExpanded = ref(false)
const propertiesOpen = ref(false)

const task = computed(() => taskStore.tasks.find((item) => item.id === props.taskId) || taskStore.taskByKeyCache[props.taskId] || null)
const priorityIcons = { urgent: PriorityUrgentIcon, high: PriorityHighIcon, medium: PriorityMediumIcon, low: PriorityLowIcon } satisfies Record<Priority, typeof PriorityUrgentIcon>
const statusLabels: Record<Status, string> = { backlog: '待规划', todo: '待处理', in_progress: '进行中', in_review: '待评审', done: '已完成', canceled: '已取消', duplicate: '重复' }
const priorityLabels: Record<Priority, string> = { urgent: '紧急', high: '高', medium: '中', low: '低' }
const projectName = computed(() => {
  const projectId = task.value?.projectId ?? projectStore.activeProjectId
  return projectStore.projects.find((project) => project.id === projectId)?.name || '未选择项目'
})
const assigneeName = computed(() => {
  if (!task.value) return '未分配'
  return users.value.find((user) => user.id === task.value?.assigneeId)?.username || task.value.assigneeDisplayName || '未分配'
})
const hasLongDescription = computed(() => description.value.trim().length > 170)
const descriptionHtml = computed(() => renderBody(description.value, renderMarkdown))

function toDateInput(value?: number | null) {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 10)
}

function localizedDate(value?: number | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) : '未设置'
}

function localizedShortDate(value?: number | null) {
  if (!value) return '无日期'
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

async function runAction(action: () => Promise<unknown>, fallbackMessage: string) {
  actionError.value = ''
  retryAction.value = null
  try {
    await action()
    return true
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : fallbackMessage
    retryAction.value = async () => { await runAction(action, fallbackMessage) }
    return false
  }
}

async function updateField(patch: { status?: Status; priority?: Priority; assigneeId?: number; clearAssignee?: boolean; dueDate?: number; clearDueDate?: boolean }) {
  if (!task.value) return
  const taskId = task.value.id
  savingField.value = true
  try { await runAction(() => taskStore.updateTask(taskId, patch), '任务属性保存失败，请重试。') } finally { savingField.value = false }
}

async function saveText() {
  if (!task.value) return false
  const patch: { title?: string; description?: string } = {}
  if (title.value.trim() && title.value.trim() !== task.value.title) patch.title = title.value.trim()
  if (description.value !== (task.value.description || '')) patch.description = description.value
  if (!Object.keys(patch).length) return true
  const taskId = task.value.id
  savingField.value = true
  try { return await runAction(() => taskStore.updateTask(taskId, patch), '任务内容保存失败，请重试。') } finally { savingField.value = false }
}

function startContentEdit() {
  if (!task.value) return
  title.value = task.value.title
  description.value = task.value.description || ''
  contentEditing.value = true
}

function cancelContentEdit() {
  if (!task.value) return
  title.value = task.value.title
  description.value = task.value.description || ''
  contentEditing.value = false
}

async function saveContentEdit() {
  if (await saveText()) contentEditing.value = false
}

async function addComment() {
  if (!task.value || !commentBody.value.trim() || savingComment.value) return
  const taskId = task.value.id
  const body = commentBody.value.trim()
  savingComment.value = true
  try {
    await runAction(async () => {
      const created = await taskCommentsApi.create(taskId, { body, mentionedUserIds: [], parentId: null })
      comments.value = [...comments.value, created]
      commentBody.value = ''
    }, '评论发送失败，请重试。')
  } finally { savingComment.value = false }
}

async function deleteComment(commentId: number) {
  if (!task.value) return
  const taskId = task.value.id
  await runAction(async () => {
    await taskCommentsApi.delete(taskId, commentId)
    comments.value = comments.value.filter((item) => item.id !== commentId)
  }, '评论删除失败，请重试。')
}

async function uploadAttachment(event: Event) {
  if (!task.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const taskId = task.value.id
  uploading.value = true
  try {
    await runAction(async () => {
      attachments.value = [...attachments.value, await attachmentsApi.upload(taskId, file)]
    }, '附件上传失败，请重试。')
  }
  finally { uploading.value = false; input.value = '' }
}

async function downloadAttachment(attachment: TaskAttachment) {
  if (!task.value) return
  const taskId = task.value.id
  await runAction(() => attachmentsApi.download(taskId, attachment.id, attachment.fileName), '附件下载失败，请重试。')
}

async function toggleFavorite() {
  if (!task.value) return
  const currentTask = task.value
  await runAction(() => favoriteStore.toggleFavorite(currentTask), '收藏状态更新失败，请重试。')
}

async function loadTask() {
  loading.value = true
  loadError.value = ''
  try {
    const loaded = await taskStore.fetchTaskByKey(props.taskId)
    title.value = loaded.title
    description.value = loaded.description || ''
    if (loaded.projectId && projectStore.activeProjectId !== loaded.projectId) projectStore.setActiveProject(loaded.projectId)
    const projectId = loaded.projectId ?? projectStore.activeProjectId
    users.value = projectId ? await projectApi.listMembers(projectId).catch(() => []) : []
    subtasks.value = loaded.numericId ? await taskStore.fetchSubIssues(loaded.numericId, projectId).catch(() => []) : []
    const [nextComments, nextAttachments] = await Promise.all([
      taskCommentsApi.list(loaded.id).catch(() => []),
      attachmentsApi.list(loaded.id).catch(() => [])
    ])
    comments.value = nextComments
    attachments.value = nextAttachments
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '任务加载失败。'
  } finally { loading.value = false }
}

watch(() => props.taskId, loadTask)
onMounted(loadTask)
</script>

<template>
  <main class="mobile-fullscreen mobile-detail-view">
    <header class="mobile-navigation-bar">
      <button type="button" class="mobile-nav-back" aria-label="返回" @click="emit('back')"><ChevronLeft :size="24" /></button>
      <span class="mobile-detail-key">{{ task?.id || taskId }}</span>
      <div class="mobile-nav-actions">
        <Loader2 v-if="savingField" :size="18" class="mobile-spinner" aria-label="保存中" />
        <button v-if="task" type="button" class="mobile-icon-button" :aria-label="favoriteStore.isFavorite(task.id) ? '取消收藏' : '收藏任务'" @click="toggleFavorite">
          <Star :size="20" :fill="favoriteStore.isFavorite(task.id) ? 'currentColor' : 'none'" />
        </button>
      </div>
    </header>

    <div v-if="loading" class="mobile-detail-loading"><Loader2 :size="24" class="mobile-spinner" />加载任务…</div>
    <MobileEmptyState v-else-if="loadError || !task" title="任务加载失败" :description="loadError || '未找到该任务。'" action="重试" @action="loadTask" />

    <div v-else class="mobile-detail-content">
      <div v-if="actionError" class="mobile-action-error" role="alert">
        <span>{{ actionError }}</span>
        <button v-if="retryAction" type="button" @click="retryAction">重试</button>
      </div>
      <template v-if="contentEditing">
        <section class="mobile-detail-editor">
          <div class="mobile-detail-editor-heading"><span>编辑任务内容</span><button type="button" @click="cancelContentEdit">取消</button></div>
          <input v-model="title" class="mobile-detail-title" aria-label="任务标题">
          <textarea v-model="description" class="mobile-detail-description" rows="8" placeholder="添加任务描述…" aria-label="任务描述" />
          <button type="button" class="mobile-detail-save-content" :disabled="savingField || !title.trim()" @click="saveContentEdit">{{ savingField ? '保存中…' : '保存内容' }}</button>
        </section>
      </template>
      <template v-else>
        <section class="mobile-detail-hero">
          <div class="mobile-detail-state-line"><span class="mobile-detail-project-name">{{ projectName }}</span><i aria-hidden="true" /><MobileStatusGlyph :status="task.status" :size="18" /><span>{{ statusLabels[task.status] }}</span><i aria-hidden="true" /><component :is="priorityIcons[task.priority]" :size="16" class="mobile-priority-icon" /><span>{{ priorityLabels[task.priority] }}优先级</span></div>
          <div class="mobile-detail-title-line"><h1>{{ title }}</h1><button type="button" class="mobile-icon-button mobile-detail-edit-button" aria-label="编辑任务内容" @click="startContentEdit"><Pencil :size="19" /></button></div>
          <div v-if="description" class="mobile-detail-description-copy markdown-body" :class="{ 'is-collapsed': hasLongDescription && !descriptionExpanded }" v-html="descriptionHtml" />
          <button v-if="hasLongDescription" type="button" class="mobile-detail-expand" @click="descriptionExpanded = !descriptionExpanded">{{ descriptionExpanded ? '收起描述' : '展开描述' }}</button>
          <button v-else-if="!description" type="button" class="mobile-detail-add-description" @click="startContentEdit"><Pencil :size="16" />添加描述</button>
          <div v-if="task.labels?.length" class="mobile-label-list mobile-detail-labels"><span v-for="label in task.labels" :key="label.id">{{ label.name }}</span></div>
        </section>

        <section class="mobile-detail-info-section" aria-label="任务信息">
          <button type="button" class="mobile-task-facts" :aria-label="`编辑任务信息：${statusLabels[task.status]}，${priorityLabels[task.priority]}优先级，负责人${assigneeName}，截止${localizedDate(task.dueDate)}`" @click="propertiesOpen = true">
            <span><MobileStatusGlyph :status="task.status" :size="18" /><strong>{{ statusLabels[task.status] }}</strong></span>
            <span><component :is="priorityIcons[task.priority]" :size="17" class="mobile-priority-icon" /><strong>{{ priorityLabels[task.priority] }}</strong></span>
            <span><UserRound :size="18" /><strong>{{ assigneeName }}</strong></span>
            <span><CalendarDays :size="18" /><strong>{{ localizedShortDate(task.dueDate) }}</strong></span>
            <ChevronRight class="mobile-task-facts-chevron" :size="18" aria-hidden="true" />
          </button>
        </section>
      </template>

      <section class="mobile-detail-section mobile-detail-work-section">
        <h2>子任务 <span>{{ subtasks.length }}</span></h2>
        <div v-if="subtasks.length" class="mobile-subtask-list"><MobileTaskRow v-for="subtask in subtasks" :key="subtask.id" :task="subtask" :users="users" @open="emit('open-task', $event)" /></div>
        <p v-else class="mobile-section-empty">暂无子任务</p>
      </section>

      <section class="mobile-detail-section mobile-detail-work-section">
        <div class="mobile-section-heading"><h2>附件 <span>{{ attachments.length }}</span></h2><label class="mobile-text-action"><Paperclip :size="17" />{{ uploading ? '上传中' : '添加' }}<input type="file" hidden :disabled="uploading" @change="uploadAttachment"></label></div>
        <div v-if="attachments.length" class="mobile-attachment-list">
          <button v-for="attachment in attachments" :key="attachment.id" type="button" @click="downloadAttachment(attachment)"><FileText :size="18" /><span>{{ attachment.fileName }}</span><ArrowUpFromLine :size="16" /></button>
        </div>
        <p v-else class="mobile-section-empty">暂无附件</p>
      </section>

      <section class="mobile-detail-section mobile-comments-section mobile-detail-work-section">
        <h2>评论 <span>{{ comments.length }}</span></h2>
        <div v-if="comments.length" class="mobile-comment-list">
          <article v-for="comment in comments" :key="comment.id">
            <span class="mobile-avatar mobile-avatar--small" :style="getAvatarColorByUsername(comment.authorName)">{{ getInitials(comment.authorName) }}</span>
            <div><header><strong>{{ comment.authorName }}</strong><time>{{ new Date(comment.createdAt).toLocaleDateString('zh-CN') }}</time></header><p>{{ comment.body }}</p></div>
            <button v-if="comment.deletable" type="button" aria-label="删除评论" @click="deleteComment(comment.id)"><Trash2 :size="16" /></button>
          </article>
        </div>
        <form class="mobile-comment-composer" @submit.prevent="addComment">
          <MessageCircle :size="18" aria-hidden="true" /><textarea v-model="commentBody" rows="2" placeholder="添加评论…" aria-label="评论内容" />
          <button type="submit" :disabled="!commentBody.trim() || savingComment">{{ savingComment ? '发送中' : '发送' }}</button>
        </form>
      </section>
    </div>

    <MobileBottomSheet v-model="propertiesOpen" title="编辑任务信息">
      <div class="mobile-detail-sheet-fields">
        <label><span><MobileStatusGlyph :status="task?.status || 'todo'" :size="19" />状态</span><select :value="task?.status" @change="updateField({ status: ($event.target as HTMLSelectElement).value as Status })"><option value="backlog">待规划</option><option value="todo">待处理</option><option value="in_progress">进行中</option><option value="in_review">待评审</option><option value="done">已完成</option><option value="canceled">已取消</option><option value="duplicate">重复</option></select></label>
        <label><span><component :is="priorityIcons[task?.priority || 'medium']" :size="17" class="mobile-priority-icon" />优先级</span><select :value="task?.priority" @change="updateField({ priority: ($event.target as HTMLSelectElement).value as Priority })"><option value="low">低</option><option value="medium">中</option><option value="high">高</option><option value="urgent">紧急</option></select></label>
        <label><span><UserRound :size="18" />负责人</span><select :value="task?.assigneeId ?? ''" @change="($event.target as HTMLSelectElement).value ? updateField({ assigneeId: Number(($event.target as HTMLSelectElement).value) }) : updateField({ clearAssignee: true })"><option value="">未分配</option><option v-for="user in users" :key="user.id" :value="user.id">{{ user.username }}</option></select></label>
        <label><span><CalendarDays :size="18" />截止日期</span><span class="mobile-date-control"><span>{{ localizedDate(task?.dueDate) }}</span><input type="date" lang="zh-CN" aria-label="选择截止日期" :value="toDateInput(task?.dueDate)" @change="($event.target as HTMLInputElement).value ? updateField({ dueDate: new Date(`${($event.target as HTMLInputElement).value}T23:59:59`).getTime() }) : updateField({ clearDueDate: true })"></span></label>
      </div>
    </MobileBottomSheet>
  </main>
</template>
