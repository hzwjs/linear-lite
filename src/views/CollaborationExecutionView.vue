<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore'
import { useProjectStore } from '../store/projectStore'
import { taskApi } from '../services/api/task'
import { collaborationHostApi, type HostDeviceAuthorization } from '../services/api/collaborationHost'
import { projectDeviceBindingApi, type ProjectDeviceBinding } from '../services/api/projectDeviceBinding'
import { collaborationBatchApi, type CollaborationBatchDetail, type CollaborationBatchStart, type CollaborationBatchSummary } from '../services/api/collaborationBatch'
import { clearPendingBatchStart, readPendingBatchStart, savePendingBatchStart } from '../utils/collaborationBatchStart'
import { collaborationBatchTone } from '../utils/collaborationBatchDisplay'
import { buildTaskRoute } from '../utils/taskRoute'
import { formatBeijingDateTime } from '../utils/beijingTime'
import CollaborationStatusBadge from '../components/ui/CollaborationStatusBadge.vue'
import CollaborationTaskFacts from '../components/CollaborationTaskFacts.vue'
import CollaborationGoalAuthorization from '../components/CollaborationGoalAuthorization.vue'
import type { Task } from '../types/domain'

const route = useRoute(), router = useRouter(), auth = useAuthStore(), projects = useProjectStore()
const { t, te } = useI18n()
const projectId = computed(() => Number(route.params.projectId))
const userId = computed(() => auth.currentUser?.id)
const project = computed(() => projects.projects.find(item => item.id === projectId.value))
const selectedKeys = computed(() => {
  const input = route.query.tasks
  const values = Array.isArray(input) ? input : input == null ? [] : [input]
  return values.filter((value): value is string => typeof value === 'string' && value.length > 0)
})
const currentBatchId = computed(() => typeof route.query.batch === 'string' ? route.query.batch : null)
const tasks = ref<Task[]>([])
const bindings = ref<ProjectDeviceBinding[]>([])
const grants = ref<HostDeviceAuthorization[]>([])
const batches = ref<CollaborationBatchSummary[]>([])
const detail = ref<CollaborationBatchDetail | null>(null)
const hasMore = ref(false), nextCursor = ref<string | null>(null)
const loading = ref(false), busy = ref(false), moreBusy = ref(false)
const error = ref(''), factsError = ref(''), startError = ref(''), storageError = ref('')
const pending = ref<CollaborationBatchStart | null>(null)
const selectedBindingId = ref<number | null>(null)
const hours = ref(1)
let sequence = 0, factsSequence = 0, pagesLoaded = 1
let historyRequest: { sequence: number; batchId: string | null } | null = null
let timer: ReturnType<typeof setInterval> | undefined

const selectedTasks = computed(() => selectedKeys.value.map(key => tasks.value.find(task => task.id === key)))
const selectionValid = computed(() => selectedKeys.value.length > 0 && new Set(selectedKeys.value).size === selectedKeys.value.length
  && selectedTasks.value.every(task => task?.projectId === projectId.value && Number.isSafeInteger(task.numericId) && task.numericId! > 0))
const binding = computed(() => bindings.value.find(item => item.id === selectedBindingId.value))
const validBudget = computed(() => Number.isFinite(hours.value) && hours.value > 0
  && Number.isSafeInteger(hours.value * 3600000) && hours.value * 3600000 > 0)

function grantFor(deviceId: string) { return grants.value.find(grant => grant.deviceId === deviceId && grant.authorized) }
function grantLabel(deviceId: string) {
  const grant = grantFor(deviceId)
  if (!grant) return t('collaborationBatch.unauthorized')
  return grant.online ? grant.deviceName : `${grant.deviceName} · ${t('collaborationBatch.offline')}`
}
function current(seq: number, pid: number, uid: number | undefined) { return sequence === seq && projectId.value === pid && userId.value === uid }
function message(reason: unknown, fallback: string) { return reason instanceof Error ? reason.message : fallback }
function stateLabel(value: string) {
  const key = `collaborationBatch.state.${value}`
  return te(key) ? t(key) : t('collaborationBatch.unknownState', { state: value })
}
function reasonLabel(value: string) {
  const key = `collaborationBatch.reason.${value}`
  return te(key) ? t(key) : t('collaborationBatch.unknownReason', { reason: value })
}
function taskName(id: number) {
  const task = tasks.value.find(item => item.numericId === id)
  return task ? `${task.id} ${task.title}` : t('collaborationBatch.taskUnavailable')
}
function deviceName(batch: CollaborationBatchSummary) {
  const found = bindings.value.find(item => item.id === batch.bindingId)
  return found ? grantLabel(found.deviceId) : t('collaborationBatch.bindingChanged')
}
function batchDirectory(batch: { bindingId: number; bindingRevision: number }) {
  const found = bindings.value.find(item => item.id === batch.bindingId)
  return found && found.revision === batch.bindingRevision ? found.projectDirectory : t('collaborationBatch.bindingChanged')
}
function date(value: string | null) {
  if (!value) return t('collaborationBatch.none')
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : formatBeijingDateTime(value)
}
function openBatch(batchId: string) {
  void router.push({ name: 'project-collaboration', params: { projectId: projectId.value }, query: { batch: batchId } })
}
function openTask(taskKey: string) { void router.push(buildTaskRoute(taskKey, projectId.value)) }

async function refreshHistory() {
  const uid = userId.value, pid = projectId.value, seq = sequence, batchId = currentBatchId.value
  if (!uid || !Number.isSafeInteger(pid) || pid <= 0
      || (historyRequest?.sequence === seq && historyRequest.batchId === batchId)) return
  const request = { sequence: seq, batchId }
  historyRequest = request
  try {
    const pages: CollaborationBatchSummary[] = []
    let cursor: string | null = null, more = false
    for (let pageNumber = 0; pageNumber < pagesLoaded; pageNumber++) {
      const page = await collaborationBatchApi.list(pid, cursor)
      pages.push(...page.items); more = page.hasMore
      if (page.hasMore && (!page.nextCursor || page.nextCursor === cursor)) throw new Error(t('collaborationBatch.loadFailed'))
      cursor = page.nextCursor
      if (!more) break
    }
    if (!current(seq, pid, uid) || currentBatchId.value !== batchId) return
    batches.value = pages; hasMore.value = more; nextCursor.value = cursor
    const nextDetail = batchId ? await collaborationBatchApi.get(pid, batchId) : null
    if (!current(seq, pid, uid) || currentBatchId.value !== batchId) return
    detail.value = nextDetail; error.value = ''
  } catch (reason) {
    if (current(seq, pid, uid) && currentBatchId.value === batchId) error.value = message(reason, t('collaborationBatch.loadFailed'))
  } finally { if (historyRequest === request) historyRequest = null }
}

async function loadMore() {
  if (!hasMore.value || !nextCursor.value || moreBusy.value || historyRequest?.sequence === sequence) return
  moreBusy.value = true
  const uid = userId.value, pid = projectId.value, seq = sequence, cursor = nextCursor.value
  try {
    const page = await collaborationBatchApi.list(pid, cursor)
    if (!current(seq, pid, uid)) return
    if (page.hasMore && (!page.nextCursor || page.nextCursor === cursor)) throw new Error(t('collaborationBatch.loadFailed'))
    batches.value.push(...page.items); nextCursor.value = page.nextCursor; hasMore.value = page.hasMore; pagesLoaded++
  } catch (reason) { if (current(seq, pid, uid)) error.value = message(reason, t('collaborationBatch.loadFailed')) }
  finally { if (current(seq, pid, uid)) moreBusy.value = false }
}

async function initialize() {
  const uid = userId.value, pid = projectId.value
  sequence++
  tasks.value = []; grants.value = []; bindings.value = []; batches.value = []; detail.value = null
  pending.value = null; selectedBindingId.value = null; storageError.value = ''; error.value = ''; factsError.value = ''; startError.value = ''
  busy.value = false; moreBusy.value = false
  pagesLoaded = 1; hasMore.value = false; nextCursor.value = null
  if (!uid || !Number.isSafeInteger(pid) || pid <= 0) return
  if (projects.activeProjectId !== pid) projects.setActiveProject(pid)
  try { pending.value = readPendingBatchStart(uid, pid) }
  catch { storageError.value = t('collaborationBatch.storageInvalid') }
  await Promise.all([refreshFacts(), refreshHistory()])
}

/** 更新设备和任务事实，保留用户选择的运行时长。 */
async function refreshFacts() {
  const uid = userId.value, pid = projectId.value, seq = sequence, run = ++factsSequence
  if (!uid || !Number.isSafeInteger(pid) || pid <= 0) return
  loading.value = true
  try {
    const [allTasks, allBindings, allGrants] = await Promise.all([
      taskApi.list(pid), projectDeviceBindingApi.list(pid), collaborationHostApi.list(),
    ])
    if (!current(seq, pid, uid) || factsSequence !== run) return
    tasks.value = allTasks; bindings.value = allBindings; grants.value = allGrants
    const available = allBindings.find(item => allGrants.some(grant => grant.deviceId === item.deviceId && grant.authorized && grant.online))
    if (!allBindings.some(item => item.id === selectedBindingId.value)) selectedBindingId.value = available?.id ?? null
    factsError.value = ''
  } catch (reason) {
    if (current(seq, pid, uid) && factsSequence === run) {
      grants.value = []
      factsError.value = message(reason, t('collaborationBatch.loadFailed'))
    }
  } finally { if (current(seq, pid, uid) && factsSequence === run) loading.value = false }
}

async function start() {
  const uid = userId.value, pid = projectId.value, seq = sequence
  const requestRoute = JSON.stringify(route.query)
  if (!uid || busy.value || storageError.value) return
  let input = pending.value
  if (!input) {
    try {
      const existing = readPendingBatchStart(uid, pid)
      if (existing) { pending.value = existing; return }
    } catch { storageError.value = t('collaborationBatch.storageInvalid'); return }
    if (!selectionValid.value || !binding.value || !grantFor(binding.value.deviceId)?.online || !validBudget.value) return
    input = { projectId: pid, operationId: crypto.randomUUID(), bindingId: binding.value.id,
      bindingRevision: binding.value.revision, taskIds: selectedTasks.value.map(task => task!.numericId!),
      maxDurationMs: hours.value * 3600000 }
    try { savePendingBatchStart(uid, input); pending.value = input }
    catch { storageError.value = t('collaborationBatch.storageFailed'); return }
  }
  busy.value = true; startError.value = ''
  try {
    const result = await collaborationBatchApi.start(input)
    if (!result || typeof result.batchId !== 'string' || !result.batchId) throw new Error(t('collaborationBatch.startFailed'))
    if (!current(seq, pid, uid) || JSON.stringify(route.query) !== requestRoute) return
    // 已离开的请求不得清除另一次启动的待确认记录。
    if (pending.value?.operationId !== input.operationId) return
    await router.replace({ name: 'project-collaboration', params: { projectId: pid }, query: { batch: result.batchId } })
    if (!current(seq, pid, uid) || pending.value?.operationId !== input.operationId) return
    if (route.query.batch !== result.batchId) throw new Error(t('collaborationBatch.loadFailed'))
    clearPendingBatchStart(uid, pid)
    pending.value = null
    await refreshHistory()
  } catch (reason) { if (current(seq, pid, uid)) startError.value = message(reason, t('collaborationBatch.startFailed')) }
  finally { if (current(seq, pid, uid)) busy.value = false }
}

function abandon() {
  const uid = userId.value
  if (!uid || busy.value || !window.confirm(t('collaborationBatch.abandonConfirm'))) return
  try { clearPendingBatchStart(uid, projectId.value); pending.value = null; storageError.value = ''; startError.value = '' }
  catch { storageError.value = t('collaborationBatch.storageFailed') }
}

function refreshPendingFromStorage(event: StorageEvent) {
  if (event.storageArea !== localStorage || !userId.value) return
  try {
    pending.value = readPendingBatchStart(userId.value, projectId.value)
    storageError.value = ''
  } catch { storageError.value = t('collaborationBatch.storageInvalid') }
}

async function stop(batch: CollaborationBatchSummary) {
  const uid = userId.value, pid = projectId.value, seq = sequence
  if (!uid || busy.value || !window.confirm(t('collaborationBatch.stopConfirm'))) return
  busy.value = true; error.value = ''
  try { await collaborationBatchApi.stop(pid, batch.batchId, crypto.randomUUID()); if (current(seq, pid, uid)) await refreshHistory() }
  catch (reason) { if (current(seq, pid, uid)) error.value = message(reason, t('collaborationBatch.stopFailed')) }
  finally { if (current(seq, pid, uid)) busy.value = false }
}

watch(() => [projectId.value, userId.value] as const, () => { void initialize() }, { immediate: true })
watch(currentBatchId, () => { detail.value = null; void refreshHistory() })
timer = setInterval(() => { void refreshHistory() }, 5000)
window.addEventListener('storage', refreshPendingFromStorage)
onUnmounted(() => { sequence++; clearInterval(timer); window.removeEventListener('storage', refreshPendingFromStorage) })
</script>

<template>
  <section class="collaboration-view" aria-labelledby="collaboration-heading">
    <header class="collaboration-header"><div><span class="project-name">{{ project?.name }}</span><h1 id="collaboration-heading">{{ t('collaborationBatch.title') }}</h1></div><button type="button" class="button" data-testid="batch-refresh" @click="refreshFacts(); refreshHistory()">{{ t('collaborationBatch.refresh') }}</button></header>
    <p v-if="loading" role="status">{{ t('collaborationBatch.loading') }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="factsError" class="error" role="alert">{{ factsError }}</p>
    <section v-if="selectedKeys.length || pending || storageError" class="panel start-panel">
      <h2>{{ t('collaborationBatch.start') }}</h2>
      <template v-if="pending">
        <h3>{{ t('collaborationBatch.pending') }}</h3>
        <div><strong>{{ t('collaborationBatch.pendingSelection') }}</strong><ul><li v-for="id in pending.taskIds" :key="id">{{ taskName(id) }}</li></ul></div>
        <p>{{ t('collaborationBatch.pendingBinding') }}: {{ batchDirectory(pending) }}</p>
        <p>{{ t('collaborationBatch.durationHours') }}: {{ pending.maxDurationMs / 3600000 }}</p>
        <div class="actions"><button class="primary" type="button" data-testid="batch-retry" :disabled="busy" @click="start">{{ t('collaborationBatch.retryStart') }}</button><button class="button" type="button" :disabled="busy" @click="abandon">{{ t('collaborationBatch.abandon') }}</button></div>
      </template>
      <template v-else-if="!storageError">
        <h3>{{ t('collaborationBatch.selectedTasks') }}</h3>
        <p v-if="!selectionValid && !loading" class="error" role="alert">{{ t('collaborationBatch.selectionFailed') }}</p>
        <ul class="selected-list"><li v-for="(task, index) in selectedTasks" :key="selectedKeys[index]"><template v-if="task"><button class="link" type="button" @click="openTask(task.id)">{{ task.id }} {{ task.title }}</button></template><span v-else>{{ t('collaborationBatch.taskUnavailable') }}</span></li></ul>
        <div class="form-grid"><label for="batch-device">{{ t('collaborationBatch.device') }}</label><select id="batch-device" v-model="selectedBindingId"><option :value="null">{{ t('collaborationBatch.selectDevice') }}</option><option v-for="item in bindings" :key="item.id" :value="item.id" :disabled="!grantFor(item.deviceId)?.online">{{ grantLabel(item.deviceId) }} · {{ item.projectDirectory }}</option></select>
          <label for="batch-hours">{{ t('collaborationBatch.durationHours') }}</label><input id="batch-hours" v-model.number="hours" type="number" min="0.001" step="0.25" />
        </div>
        <p v-if="binding">{{ t('collaborationBatch.directory') }}: {{ binding.projectDirectory }}</p>
        <p v-if="!validBudget" class="error">{{ t('collaborationBatch.invalidBudget') }}</p>
        <div class="actions"><button type="button" class="primary" data-testid="batch-start" :disabled="busy || loading || !selectionValid || !binding || !grantFor(binding.deviceId)?.online || !validBudget" @click="start">{{ busy ? t('collaborationBatch.starting') : t('collaborationBatch.confirm') }}</button><button type="button" class="button" :disabled="busy" @click="router.replace({ name: 'project-collaboration', params: { projectId }, query: {} })">{{ t('collaborationBatch.cancel') }}</button><router-link :to="`/projects/${projectId}/settings`">{{ t('collaborationBatch.deviceSettings') }}</router-link></div>
      </template>
      <p v-if="storageError" class="error" role="alert">{{ storageError }}</p><button v-if="storageError" type="button" class="button" @click="abandon">{{ t('collaborationBatch.abandon') }}</button>
      <p v-if="startError" class="error" role="alert">{{ startError }}</p>
    </section>
    <div class="content-grid">
      <section class="panel history"><h2>{{ t('collaborationBatch.history') }}</h2><p v-if="!loading && !batches.length">{{ t('collaborationBatch.empty') }}</p>
        <ul class="batch-list"><li v-for="item in batches" :key="item.batchId"><button type="button" class="batch-row" @click="openBatch(item.batchId)"><span>{{ date(item.createdAt) }}</span><span>{{ deviceName(item) }}</span><CollaborationStatusBadge :tone="collaborationBatchTone(item.displayState, item.endReason)">{{ stateLabel(item.displayState) }}<template v-if="item.endReason"> · {{ reasonLabel(item.endReason) }}</template></CollaborationStatusBadge></button></li></ul>
        <button v-if="hasMore" type="button" class="button" :disabled="moreBusy" @click="loadMore">{{ t('collaborationBatch.more') }}</button>
      </section>
      <section v-if="detail" class="panel detail"><div class="detail-heading"><h2>{{ t('collaborationBatch.detail') }}</h2><CollaborationStatusBadge :tone="collaborationBatchTone(detail.batch.displayState, detail.batch.endReason)">{{ stateLabel(detail.batch.displayState) }}</CollaborationStatusBadge></div>
        <dl class="facts"><div><dt>{{ t('collaborationBatch.device') }}</dt><dd>{{ deviceName(detail.batch) }}</dd></div><div><dt>{{ t('collaborationBatch.directory') }}</dt><dd>{{ batchDirectory(detail.batch) }}</dd></div><div><dt>{{ t('collaborationBatch.slots') }}</dt><dd>{{ detail.batch.maxSlots }}</dd></div><div><dt>{{ t('collaborationBatch.durationHours') }}</dt><dd>{{ detail.batch.maxDurationMs / 3600000 }}</dd></div><div><dt>{{ t('collaborationBatch.attempts') }}</dt><dd>{{ detail.batch.attemptsUsed }} / {{ detail.batch.maxAttempts }}</dd></div><div><dt>{{ t('collaborationBatch.startedAt') }}</dt><dd>{{ date(detail.batch.startedAt) }}</dd></div><div><dt>{{ t('collaborationBatch.deadline') }}</dt><dd>{{ date(detail.batch.deadlineAt) }}</dd></div><div v-if="detail.batch.endedAt"><dt>{{ t('collaborationBatch.endedAt') }}</dt><dd>{{ date(detail.batch.endedAt) }}</dd></div><div v-if="detail.batch.endReason"><dt>{{ t('collaborationBatch.endReason') }}</dt><dd>{{ reasonLabel(detail.batch.endReason) }}</dd></div></dl>
        <button v-if="detail.batch.lifecycle !== 'ended' && detail.batch.desiredIntent === 'RUN'" type="button" class="button" data-testid="batch-stop" :disabled="busy" @click="stop(detail.batch)">{{ t('collaborationBatch.stop') }}</button>
        <article v-for="item in detail.tasks" :key="item.taskId" class="task-detail"><h3><button v-if="item.taskKey" class="link" type="button" @click="openTask(item.taskKey)">{{ item.taskKey }} {{ item.title }}</button><span v-else>{{ t('collaborationBatch.taskUnavailable') }}</span></h3>
          <CollaborationStatusBadge v-if="item.inputsWaitingReason" tone="warning">{{ item.inputsWaitingReason }}</CollaborationStatusBadge>
          <CollaborationTaskFacts :attempts="item.attempts" :context-facts="item.contextFacts"
            :prerequisite-review-facts="item.prerequisiteReviewFacts" :prerequisite-review-statuses="item.prerequisiteReviewStatuses"
            :dependency-facts="item.dependencyFacts" :active-or-frozen="item.activeOrFrozen" :server-time="detail.serverTime">
            <template #goal-authorization><CollaborationGoalAuthorization :project-id="projectId"
              :requests="item.goalAuthorizationRequests" @authorized="refreshHistory" /></template>
          </CollaborationTaskFacts>
        </article>
      </section>
    </div>
  </section>
</template>

<style scoped>
.collaboration-view { --batch-gap: 16px; --batch-panel-padding: 20px; --batch-view-padding: 24px; --batch-mobile-padding: 16px; --batch-touch-size: 44px; flex: 1; min-width: 0; overflow: auto; padding: var(--batch-view-padding); color: var(--color-text-primary); }
.collaboration-header, .detail-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--batch-gap); }
.project-name { color: var(--color-text-secondary); font-size: var(--font-size-caption); }
h1 { margin: 4px 0 0; font-size: var(--font-size-subhead); } h2 { margin: 0 0 16px; font-size: var(--font-size-subhead); } h3 { margin: 0 0 8px; font-size: var(--font-size-body); }
.panel { padding: var(--batch-panel-padding); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-primary); }
.start-panel, .content-grid { margin-top: var(--batch-gap); }
.content-grid { display: grid; grid-template-columns: minmax(240px, 1fr) minmax(0, 2fr); gap: var(--batch-gap); align-items: start; }
.selected-list, .batch-list { display: grid; gap: 8px; list-style: none; padding: 0; } .selected-list li { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; overflow-wrap: anywhere; }
.form-grid { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(180px, 2fr); gap: 8px 12px; align-items: center; max-width: 680px; }
input, select { min-width: 0; padding: 8px 10px; color: inherit; background: var(--color-bg-primary); border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); }
.button, .primary { min-height: 36px; padding: 7px 12px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg-primary); color: inherit; cursor: pointer; }.primary { background: var(--color-accent); border-color: var(--color-accent); color: var(--color-text-on-accent); }.button:disabled, .primary:disabled { opacity: .55; cursor: not-allowed; }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 12px; }.link { max-width: 100%; padding: 0; border: 0; background: none; color: var(--color-accent); text-align: left; overflow-wrap: anywhere; cursor: pointer; }
.batch-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 10px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-bg-primary); color: inherit; text-align: left; cursor: pointer; }.batch-row span { min-width: 0; max-width: 100%; overflow-wrap: anywhere; }
.facts { display: flex; flex-wrap: wrap; gap: 12px 24px; }.facts div { min-width: 0; max-width: 100%; }.facts dt { font-size: var(--font-size-caption); color: var(--color-text-secondary); }.facts dd { margin: 4px 0 0; overflow-wrap: anywhere; }.task-detail { display: grid; gap: 8px; min-width: 0; padding-top: 16px; margin-top: 16px; border-top: 1px solid var(--color-border); }.task-detail p { margin: 0; }.task-detail p, .start-panel p { overflow-wrap: anywhere; }
.error { color: var(--color-danger); }.warning { color: var(--color-attention); }
@media (max-width: 850px) { .content-grid { grid-template-columns: 1fr; }.collaboration-view { padding: var(--batch-mobile-padding); } }
@media (max-width: 600px) { .form-grid { grid-template-columns: minmax(0, 1fr); } input, select, .button, .primary, .batch-row, .link, .actions a { min-height: var(--batch-touch-size); } .actions a { display: inline-flex; align-items: center; } }
</style>
