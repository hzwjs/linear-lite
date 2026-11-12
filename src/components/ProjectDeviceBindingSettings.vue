<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../store/authStore'
import { connectCodexHost, getCodexHostIdentity, getCodexHostStatus, validateCodexProjectDirectory, type CodexHostIdentity, type CodexHostStatus } from '../services/codexHost'
import { projectDeviceBindingApi, type ProjectDeviceBinding } from '../services/api/projectDeviceBinding'
import { collaborationHostApi, type HostDeviceAuthorization } from '../services/api/collaborationHost'
import { collaborationBatchApi, type CollaborationBatchSummary } from '../services/api/collaborationBatch'

const props = defineProps<{ projectId: number; projectName: string }>()
const { t } = useI18n()
const auth = useAuthStore()
const host = ref<CodexHostIdentity | null>(null)
const localStatus = ref<CodexHostStatus | null>(null)
const authorizations = ref<HostDeviceAuthorization[]>([])
const bindingBatches = ref<CollaborationBatchSummary[]>([])
const bindings = ref<ProjectDeviceBinding[]>([])
const bindingsLoaded = ref(false)
const selectedDeviceId = ref('')
const projectDirectory = ref('')
const loading = ref(false)
const busy = ref(false)
const statusError = ref('')
const authorizationError = ref('')
const error = ref('')
const message = ref('')
let requestSequence = 0
let statusLoading = false
let statusTimer: ReturnType<typeof setInterval> | undefined

const selectedBinding = computed(() => bindings.value.find((binding) => binding.deviceId === selectedDeviceId.value))
const canEdit = computed(() => bindingsLoaded.value && host.value !== null && selectedDeviceId.value === host.value.deviceId)
const activeBatches = computed(() => bindingBatches.value.filter((batch) => batch.bindingId === selectedBinding.value?.id && batch.lifecycle !== 'ended'))
const deviceIds = computed(() => {
  const ids = bindings.value.map((binding) => binding.deviceId)
  for (const grant of authorizations.value) if (!ids.includes(grant.deviceId)) ids.push(grant.deviceId)
  if (host.value && !ids.includes(host.value.deviceId)) ids.unshift(host.value.deviceId)
  return ids
})
const docsDirectory = computed(() => {
  const root = projectDirectory.value
  if (!root) return ''
  const separator = /^(?:[A-Za-z]:\\|\\\\)/.test(root) ? '\\' : '/'
  const normalizedRoot = root.startsWith('/') ? root.replace(/\/+$/, '') : root.replace(/[\\/]+$/, '')
  return `${normalizedRoot}${separator}docs`
})

function currentRequest(sequence: number, projectId: number, userId: number | undefined): boolean {
  return requestSequence === sequence && props.projectId === projectId && auth.currentUser?.id === userId
}

function errorMessage(reason: unknown, fallback: string): string {
  return reason instanceof Error ? reason.message : fallback
}

function updateDirectory() {
  projectDirectory.value = selectedBinding.value ? selectedBinding.value.projectDirectory : ''
}

async function refresh() {
  const sequence = ++requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  loading.value = true
  busy.value = false
  statusError.value = ''
  authorizationError.value = ''
  error.value = ''
  message.value = ''
  host.value = null
  localStatus.value = null
  authorizations.value = []
  bindingBatches.value = []
  bindings.value = []
  bindingsLoaded.value = false
  selectedDeviceId.value = ''
  projectDirectory.value = ''
  const [bindingResult, localResult, authorizationResult] = await Promise.allSettled([
    projectDeviceBindingApi.list(projectId), getCodexHostStatus(), collaborationHostApi.list(),
  ])
  if (!currentRequest(sequence, projectId, userId)) return
  if (localResult.status === 'fulfilled') {
    localStatus.value = localResult.value
    host.value = { deviceId: localResult.value.deviceId, hostInstanceId: localResult.value.hostInstanceId }
  } else statusError.value = errorMessage(localResult.reason, t('projectDeviceBinding.hostUnavailable'))
  if (authorizationResult.status === 'fulfilled') authorizations.value = authorizationResult.value
  else authorizationError.value = errorMessage(authorizationResult.reason, t('projectDeviceBinding.authorizationsFailed'))
  if (bindingResult.status === 'fulfilled') {
    bindings.value = bindingResult.value
    bindingsLoaded.value = true
  }
  else error.value = errorMessage(bindingResult.reason, t('projectDeviceBinding.loadFailed'))
  const [firstBinding] = bindings.value
  if (host.value) selectedDeviceId.value = host.value.deviceId
  else if (firstBinding) selectedDeviceId.value = firstBinding.deviceId
  updateDirectory()
  loading.value = false
}

/** 轮询连接事实，不覆盖用户编辑中的目录。 */
async function refreshHostState() {
  if (statusLoading || loading.value || busy.value) return
  statusLoading = true
  const sequence = requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  try {
    const [localResult, authorizationResult] = await Promise.allSettled([getCodexHostStatus(), collaborationHostApi.list()])
    if (!currentRequest(sequence, projectId, userId)) return
    if (localResult.status === 'fulfilled') {
      localStatus.value = localResult.value
      host.value = { deviceId: localResult.value.deviceId, hostInstanceId: localResult.value.hostInstanceId }
      if (!selectedDeviceId.value) { selectedDeviceId.value = host.value.deviceId; updateDirectory() }
      statusError.value = ''
    } else { localStatus.value = null; host.value = null; statusError.value = errorMessage(localResult.reason, t('projectDeviceBinding.hostUnavailable')) }
    if (authorizationResult.status === 'fulfilled') { authorizations.value = authorizationResult.value; authorizationError.value = '' }
    else { authorizations.value = []; authorizationError.value = errorMessage(authorizationResult.reason, t('projectDeviceBinding.authorizationsFailed')) }
  } finally { statusLoading = false }
}

async function connect() {
  if (busy.value || !localStatus.value) return
  const sequence = requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const status = await connectCodexHost()
    if (currentRequest(sequence, projectId, userId)) localStatus.value = status
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.connectFailed'))
  } finally {
    if (currentRequest(sequence, projectId, userId)) busy.value = false
  }
}

async function revoke(grant: HostDeviceAuthorization) {
  if (busy.value || !grant.authorized || !window.confirm(t('projectDeviceBinding.revokeConfirm'))) return
  const sequence = requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await collaborationHostApi.revoke(grant.id)
    if (!currentRequest(sequence, projectId, userId)) return
    message.value = t('projectDeviceBinding.revoked')
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.revokeFailed'))
  } finally {
    if (currentRequest(sequence, projectId, userId)) { busy.value = false; void refreshHostState() }
  }
}

async function downloadInstaller() {
  if (busy.value) return
  const sequence = requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  busy.value = true
  error.value = ''
  try {
    const { blob, filename } = await collaborationHostApi.installer()
    if (!currentRequest(sequence, projectId, userId)) return
    const url = URL.createObjectURL(blob)
    try {
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      link.click()
    } finally { URL.revokeObjectURL(url) }
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.downloadFailed'))
  } finally { if (currentRequest(sequence, projectId, userId)) busy.value = false }
}

async function loadBindingBatches(sequence: number, projectId: number, userId: number | undefined) {
  try {
    const batches = await collaborationBatchApi.listAll(projectId)
    if (currentRequest(sequence, projectId, userId)) bindingBatches.value = batches
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) authorizationError.value = errorMessage(reason, t('projectDeviceBinding.batchesFailed'))
  }
}

async function stopBatch(batch: CollaborationBatchSummary) {
  if (busy.value || batch.desiredIntent !== 'RUN' || !window.confirm(t('projectDeviceBinding.stopConfirm'))) return
  const sequence = requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await collaborationBatchApi.stop(projectId, batch.batchId, crypto.randomUUID())
    if (!currentRequest(sequence, projectId, userId)) return
    message.value = t('projectDeviceBinding.stopRequested')
    await loadBindingBatches(sequence, projectId, userId)
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.stopFailed'))
  } finally { if (currentRequest(sequence, projectId, userId)) busy.value = false }
}

function selectDevice(event: Event) {
  selectedDeviceId.value = (event.target as HTMLSelectElement).value
  updateDirectory()
  error.value = ''
  message.value = ''
  bindingBatches.value = []
}

async function save() {
  if (!host.value || !canEdit.value || !projectDirectory.value.trim() || busy.value) return
  const sequence = ++requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  const deviceId = host.value.deviceId
  const directory = projectDirectory.value
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const validated = await validateCodexProjectDirectory(deviceId, directory)
    if (!currentRequest(sequence, projectId, userId)) return
    if (validated.deviceId !== deviceId || selectedDeviceId.value !== deviceId) {
      error.value = t('projectDeviceBinding.deviceChanged')
      return
    }
    let saved: ProjectDeviceBinding
    try { saved = await projectDeviceBindingApi.save(projectId, validated.deviceId, validated.projectDirectory) }
    catch (reason) { await loadBindingBatches(sequence, projectId, userId); throw reason }
    if (!currentRequest(sequence, projectId, userId)) return
    bindings.value = bindings.value.filter((binding) => binding.deviceId !== saved.deviceId).concat(saved)
    selectedDeviceId.value = saved.deviceId
    projectDirectory.value = saved.projectDirectory
    bindingBatches.value = []
    message.value = t('projectDeviceBinding.saved')
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.saveFailed'))
  } finally {
    if (currentRequest(sequence, projectId, userId)) busy.value = false
  }
}

async function remove() {
  if (!host.value || !canEdit.value || !selectedBinding.value || busy.value) return
  if (!window.confirm(t('projectDeviceBinding.deleteConfirm'))) return
  const sequence = ++requestSequence
  const projectId = props.projectId
  const userId = auth.currentUser?.id
  const deviceId = host.value.deviceId
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    // 删除前复核宿主身份；服务端负责校验执行尝试是否等待停止确认。
    const identity = await getCodexHostIdentity()
    if (!currentRequest(sequence, projectId, userId)) return
    if (identity.deviceId !== deviceId || selectedDeviceId.value !== deviceId) {
      error.value = t('projectDeviceBinding.deviceChanged')
      return
    }
    try { await projectDeviceBindingApi.delete(projectId, deviceId) }
    catch (reason) { await loadBindingBatches(sequence, projectId, userId); throw reason }
    if (!currentRequest(sequence, projectId, userId)) return
    bindings.value = bindings.value.filter((binding) => binding.deviceId !== deviceId)
    projectDirectory.value = ''
    bindingBatches.value = []
    message.value = t('projectDeviceBinding.deleted')
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.deleteFailed'))
  } finally {
    if (currentRequest(sequence, projectId, userId)) busy.value = false
  }
}

watch(() => [props.projectId, auth.currentUser?.id] as const, () => { void refresh() }, { immediate: true })
statusTimer = setInterval(() => { void refreshHostState() }, 5000)
onUnmounted(() => { requestSequence++; clearInterval(statusTimer) })
</script>

<template>
  <section id="settings-device-binding" class="settings-section project-device-binding">
    <div class="binding-heading">
      <h2>{{ t('projectDeviceBinding.title') }}</h2>
      <button type="button" class="btn-secondary" :disabled="loading || busy" @click="refresh">{{ t('projectDeviceBinding.refresh') }}</button>
    </div>
    <p v-if="loading" class="binding-state" role="status">{{ t('projectDeviceBinding.loading') }}</p>
    <template v-else>
      <div class="binding-host-actions">
        <button type="button" class="btn-secondary" :disabled="busy" @click="downloadInstaller">{{ t('projectDeviceBinding.downloadHost') }}</button>
        <button v-if="localStatus && localStatus.connection !== 'UPGRADE_REQUIRED'" type="button" class="btn-primary" :disabled="busy || localStatus.connection === 'CONNECTING'" @click="connect">
          {{ localStatus.connection === 'ONLINE' ? t('projectDeviceBinding.reauthorize') : t('projectDeviceBinding.connect') }}
        </button>
      </div>
      <dl class="binding-host-state">
        <div>
          <dt>{{ t('projectDeviceBinding.browserConnection') }}</dt>
          <dd><span class="connection-tag" :class="localStatus ? 'connection-tag--success' : 'connection-tag--warning'">{{ localStatus ? t('projectDeviceBinding.browserConnected') : t('projectDeviceBinding.browserBlocked') }}</span></dd>
        </div>
        <div v-if="localStatus">
          <dt>{{ t('projectDeviceBinding.localAuthorization') }}</dt>
          <dd><span v-if="localStatus.connection === 'UPGRADE_REQUIRED'" class="connection-tag connection-tag--warning">{{ t('projectDeviceBinding.hostState.UPGRADE_REQUIRED') }}</span><span v-else class="connection-tag" :class="localStatus.userId !== null && localStatus.userId !== auth.currentUser?.id ? 'connection-tag--warning' : `connection-tag--${localStatus.connection.toLowerCase()}`">{{ localStatus.userId !== null && localStatus.userId !== auth.currentUser?.id ? t('projectDeviceBinding.otherAccount') : t(`projectDeviceBinding.hostState.${localStatus.connection}`) }}</span></dd>
        </div>
      </dl>
      <div v-if="authorizations.length" class="authorization-list">
        <div class="authorization-heading">{{ t('projectDeviceBinding.authorizedDevices') }}</div>
        <div v-for="grant in authorizations" :key="grant.id" class="authorization-row">
          <div class="authorization-device"><strong>{{ grant.deviceName }}</strong><span>{{ grant.deviceId }}</span></div>
          <span class="connection-tag" :class="grant.authorized ? 'connection-tag--success' : 'connection-tag--warning'">{{ grant.authorized ? t('projectDeviceBinding.authorized') : t('projectDeviceBinding.revokedState') }}</span>
          <span class="connection-tag" :class="grant.online ? 'connection-tag--success' : 'connection-tag--warning'">{{ grant.online ? t('projectDeviceBinding.serverOnline') : t('projectDeviceBinding.serverOffline') }}</span>
          <span class="authorization-heartbeat">{{ t('projectDeviceBinding.lastHeartbeat') }}: {{ grant.lastHeartbeatAt ? new Date(grant.lastHeartbeatAt).toLocaleString() : '—' }}</span>
          <button v-if="grant.authorized" type="button" class="btn-secondary" :disabled="busy" @click="revoke(grant)">{{ t('projectDeviceBinding.revoke') }}</button>
        </div>
      </div>
      <dl class="binding-details">
        <div v-if="auth.currentUser"><dt>{{ t('projectDeviceBinding.user') }}</dt><dd>{{ auth.currentUser.username }}</dd></div>
        <div><dt>{{ t('projectDeviceBinding.project') }}</dt><dd>{{ projectName }}</dd></div>
      </dl>
      <div v-if="deviceIds.length" class="binding-fields">
        <label for="project-binding-device">{{ t('projectDeviceBinding.device') }}</label>
        <select id="project-binding-device" class="input" :value="selectedDeviceId" :disabled="busy" @change="selectDevice">
          <option v-for="deviceId in deviceIds" :key="deviceId" :value="deviceId">
            {{ host && deviceId === host.deviceId ? t('projectDeviceBinding.localDevice', { id: deviceId }) : deviceId }}
          </option>
        </select>
        <label for="project-binding-directory">{{ t('projectDeviceBinding.directory') }}</label>
        <input id="project-binding-directory" v-model="projectDirectory" type="text" class="input" :readonly="!canEdit"
          :disabled="busy" :placeholder="t('projectDeviceBinding.directoryPlaceholder')" autocomplete="off" />
        <div v-if="projectDirectory" class="binding-docs">
          <span>{{ t('projectDeviceBinding.docsDirectory') }}</span><output>{{ docsDirectory }}</output>
        </div>
        <div v-if="canEdit" class="binding-actions">
          <button type="button" class="btn-primary" :disabled="busy || !projectDirectory.trim()" @click="save">{{ t('projectDeviceBinding.save') }}</button>
          <button v-if="selectedBinding" type="button" class="btn-secondary" :disabled="busy" @click="remove">{{ t('projectDeviceBinding.delete') }}</button>
        </div>
        <div v-if="activeBatches.length" class="binding-blocked">
          <div v-for="batch in activeBatches" :key="batch.batchId" class="binding-actions">
            <span>{{ t('projectDeviceBinding.blockedBatch', { id: batch.batchId }) }}</span>
            <button v-if="batch.desiredIntent === 'RUN'" type="button" class="btn-secondary" :disabled="busy" @click="stopBatch(batch)">{{ t('projectDeviceBinding.stopBatch') }}</button>
            <span v-else class="connection-tag connection-tag--warning">{{ t('projectDeviceBinding.stopping') }}</span>
          </div>
          <button type="button" class="btn-secondary" :disabled="busy" @click="loadBindingBatches(requestSequence, projectId, auth.currentUser?.id)">{{ t('projectDeviceBinding.refreshBatches') }}</button>
        </div>
      </div>
    </template>
    <p v-if="statusError" class="feedback feedback--error" role="alert">{{ statusError }}</p>
    <p v-if="authorizationError" class="feedback feedback--error" role="alert">{{ authorizationError }}</p>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <p v-if="message" class="feedback feedback--success" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.project-device-binding { --binding-heading-gap: 16px; --binding-heading-size: 16px; --binding-details-gap: 24px; --binding-details-spacing: 20px; --binding-field-gap: 8px; --binding-caption-gap: 5px; --binding-state-gap: 4px; }
.binding-heading { display: flex; justify-content: space-between; align-items: center; gap: var(--binding-heading-gap); }
.binding-heading h2 { margin: 0; font-size: var(--binding-heading-size); font-weight: var(--font-weight-semibold); }
.binding-details { display: flex; flex-wrap: wrap; gap: var(--binding-details-gap); margin: var(--binding-details-spacing) 0; }
.binding-details div { min-width: 0; }
.binding-details dt, .binding-fields label, .binding-docs span { color: var(--color-text-secondary); font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.binding-details dd { margin: var(--binding-caption-gap) 0 0; color: var(--color-text-primary); overflow-wrap: anywhere; }
.binding-fields { display: grid; gap: var(--binding-field-gap); }
.binding-fields label:not(:first-child) { margin-top: var(--binding-field-gap); }
.binding-docs { display: grid; gap: var(--binding-caption-gap); margin-top: var(--binding-field-gap); }
.binding-docs output { color: var(--color-text-primary); overflow-wrap: anywhere; }
.binding-state { margin: var(--binding-state-gap) 0 0; color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.binding-actions { display: flex; flex-wrap: wrap; gap: var(--binding-field-gap); margin-top: var(--binding-field-gap); }
.binding-host-actions { display: flex; flex-wrap: wrap; gap: var(--binding-field-gap); margin-top: var(--binding-details-spacing); }
.binding-host-state { display: flex; flex-wrap: wrap; gap: var(--binding-details-gap); margin: var(--binding-details-spacing) 0; }
.binding-host-state dt, .authorization-heading { color: var(--color-text-secondary); font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.binding-host-state dd { margin: var(--binding-caption-gap) 0 0; }
.connection-tag { display: inline-flex; align-items: center; min-height: 21px; padding: 2px 6px; border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.connection-tag--success, .connection-tag--online { color: var(--color-success); }
.connection-tag--warning, .connection-tag--offline, .connection-tag--auth_required { color: var(--color-attention); }
.connection-tag--connecting { color: var(--color-status-in-progress); }
.authorization-list { display: grid; gap: var(--binding-field-gap); }
.authorization-row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--binding-field-gap); }
.authorization-device { display: grid; gap: var(--binding-caption-gap); min-width: 180px; overflow-wrap: anywhere; }
.authorization-device span, .authorization-heartbeat { color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.binding-blocked { display: grid; justify-items: start; gap: var(--binding-field-gap); }
</style>
