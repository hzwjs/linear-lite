<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../store/authStore'
import { collaborationBatchApi } from '../services/api/collaborationBatch'
import { taskCollaborationApi, type TaskCollaborationState } from '../services/api/taskCollaboration'
import type { CollaborationStatusObservation } from '../store/taskStore'
import CollaborationStatusBadge from './ui/CollaborationStatusBadge.vue'
import CollaborationTaskFacts from './CollaborationTaskFacts.vue'
import CollaborationGoalAuthorization from './CollaborationGoalAuthorization.vue'

const TERMINAL_STATUSES = new Set(['done', 'canceled', 'duplicate'])

const props = defineProps<{
  taskKey: string
  projectId: number
  taskNumericId?: number
  inputRevision?: number
  captureObservation: () => CollaborationStatusObservation | null
}>()
const emit = defineEmits<{
  observed: [taskKey: string, marker: CollaborationStatusObservation, state: TaskCollaborationState]
}>()
const { t } = useI18n()
const auth = useAuthStore()
const expanded = ref(false)
const loading = ref(false)
const startingSingle = ref(false)
const executionState = ref<TaskCollaborationState | null>(null)
const error = ref('')
const startSingleError = ref('')
const startedBatchId = ref<string | null>(null)
let sequence = 0
let singleSequence = 0
let singleOperationId: string | null = null

const reviewStatuses = computed(() => executionState.value?.bindings.flatMap(binding => binding.prerequisiteReviewStatuses) ?? [])
const canStartSingle = computed(() =>
  typeof props.taskNumericId === 'number'
  && Number.isSafeInteger(props.taskNumericId)
  && props.taskNumericId > 0
  && executionState.value != null
  && !executionState.value.activeOrFrozen
  && !TERMINAL_STATUSES.has(executionState.value.status)
)

async function refreshState() {
  const revision = props.inputRevision
  if (auth.currentUser?.id == null) return
  const request = ++sequence
  const { taskKey, projectId } = props
  const userId = auth.currentUser.id
  const marker = props.captureObservation()
  loading.value = true
  try {
    const result = await taskCollaborationApi.state(taskKey)
    if (sequence !== request || props.taskKey !== taskKey || props.projectId !== projectId
        || props.inputRevision !== revision || auth.currentUser?.id !== userId) return
    executionState.value = result
    error.value = ''
    if (marker) emit('observed', taskKey, marker, result)
  } catch (reason) {
    if (sequence !== request || props.taskKey !== taskKey || props.projectId !== projectId
        || props.inputRevision !== revision || auth.currentUser?.id !== userId) return
    executionState.value = null
    error.value = reason instanceof Error ? reason.message : t('taskCollaboration.stateFailed')
  } finally {
    if (sequence === request) loading.value = false
  }
}
function onToggle(event: Event) {
  expanded.value = (event.target as HTMLDetailsElement).open
  if (expanded.value) void refreshState()
}

async function startSingleTask() {
  if (!canStartSingle.value || startingSingle.value || auth.currentUser?.id == null) return
  const requestSeq = ++singleSequence
  const { taskKey, projectId, taskNumericId } = props
  const userId = auth.currentUser.id
  if (taskNumericId == null) return
  if (!singleOperationId) singleOperationId = crypto.randomUUID()
  const operationId = singleOperationId
  startingSingle.value = true
  startSingleError.value = ''
  try {
    const command = await collaborationBatchApi.startSingle({
      projectId,
      operationId,
      taskId: taskNumericId,
    })
    if (singleSequence !== requestSeq || props.taskKey !== taskKey || props.projectId !== projectId
        || auth.currentUser?.id !== userId) return
    singleOperationId = null
    startedBatchId.value = command.batchId
    await refreshState()
  } catch (reason) {
    if (singleSequence !== requestSeq || props.taskKey !== taskKey || props.projectId !== projectId
        || auth.currentUser?.id !== userId) return
    startSingleError.value = reason instanceof Error ? reason.message : t('taskCollaboration.startSingleFailed')
  } finally {
    if (singleSequence === requestSeq) startingSingle.value = false
  }
}

watch(() => [props.taskKey, props.projectId, auth.currentUser?.id] as const, () => {
  singleSequence++
  startingSingle.value = false
  startSingleError.value = ''
  startedBatchId.value = null
  singleOperationId = null
})

watch(() => [props.taskKey, props.projectId, props.inputRevision, auth.currentUser?.id] as const, () => {
  sequence++
  loading.value = false
  executionState.value = null
  error.value = ''
  if (expanded.value) void refreshState()
})
const statusTimer = setInterval(() => {
  if (expanded.value && !loading.value) void refreshState()
}, 5000)
onUnmounted(() => { sequence++; singleSequence++; clearInterval(statusTimer) })
</script>

<template>
  <details class="collaboration-settings" @toggle="onToggle">
    <summary>{{ t('taskCollaboration.title') }}</summary>
    <p v-if="loading && !executionState" role="status">{{ t('taskCollaboration.loading') }}</p>
    <div v-if="executionState" class="collaboration-field">
      <div v-if="canStartSingle || startedBatchId" class="collaboration-single-actions">
        <button v-if="canStartSingle" type="button" class="collaboration-single-start"
          :disabled="startingSingle" @click="startSingleTask">
          {{ startingSingle ? t('taskCollaboration.startingSingle') : t('taskCollaboration.startSingle') }}
        </button>
        <router-link v-if="startedBatchId"
          :to="{ name: 'project-collaboration', params: { projectId }, query: { batch: startedBatchId } }">
          {{ t('taskCollaboration.viewBatch') }}
        </router-link>
      </div>
      <p v-if="startSingleError" class="collaboration-error" role="alert">{{ startSingleError }}</p>
      <div v-for="binding in executionState.bindings" :key="binding.bindingId" class="collaboration-binding">
        <CollaborationStatusBadge v-if="!binding.inputsReady" tone="warning">{{ binding.inputsWaitingReason }}</CollaborationStatusBadge>
      </div>
      <CollaborationTaskFacts :attempts="executionState.attempts" :context-facts="executionState.contextFacts"
        :prerequisite-review-facts="executionState.prerequisiteReviewFacts" :prerequisite-review-statuses="reviewStatuses"
        :dependency-facts="executionState.dependencyFacts" :active-or-frozen="executionState.activeOrFrozen"
        :server-time="executionState.serverTime">
        <template #goal-authorization>
          <CollaborationGoalAuthorization :project-id="projectId" :requests="executionState.goalAuthorizationRequests" @authorized="refreshState" />
        </template>
      </CollaborationTaskFacts>
    </div>
    <div v-if="error" class="collaboration-error" role="alert">{{ error }} <button type="button" @click="refreshState">{{ t('collaborationBatch.retry') }}</button></div>
  </details>
</template>

<style scoped>
.collaboration-settings { --collaboration-section-gap: 16px; margin: 20px 0; color: var(--color-text-primary); }
.collaboration-settings summary { cursor: pointer; font-weight: var(--font-weight-semibold); }
.collaboration-settings p { font-size: var(--font-size-caption); }
.collaboration-field { display: grid; gap: var(--collaboration-section-gap); margin-top: var(--collaboration-section-gap); }
.collaboration-single-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.collaboration-single-start { min-height: 32px; padding: 5px 12px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg-primary); color: inherit; cursor: pointer; }
.collaboration-single-start:disabled { opacity: .55; cursor: not-allowed; }
.collaboration-error { color: var(--color-danger); }
</style>
