<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../store/authStore'
import { getCodexHostIdentity, validateCodexProjectDirectory, type CodexHostIdentity } from '../services/codexHost'
import { projectDeviceBindingApi, type ProjectDeviceBinding } from '../services/api/projectDeviceBinding'

const props = defineProps<{ projectId: number; projectName: string }>()
const { t } = useI18n()
const auth = useAuthStore()
const host = ref<CodexHostIdentity | null>(null)
const bindings = ref<ProjectDeviceBinding[]>([])
const bindingsLoaded = ref(false)
const selectedDeviceId = ref('')
const projectDirectory = ref('')
const loading = ref(false)
const busy = ref(false)
const hostError = ref('')
const error = ref('')
const message = ref('')
let requestSequence = 0

const selectedBinding = computed(() => bindings.value.find((binding) => binding.deviceId === selectedDeviceId.value))
const canEdit = computed(() => bindingsLoaded.value && host.value !== null && selectedDeviceId.value === host.value.deviceId)
const deviceIds = computed(() => {
  const ids = bindings.value.map((binding) => binding.deviceId)
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
  hostError.value = ''
  error.value = ''
  message.value = ''
  host.value = null
  bindings.value = []
  bindingsLoaded.value = false
  selectedDeviceId.value = ''
  projectDirectory.value = ''
  const [identityResult, bindingResult] = await Promise.allSettled([
    getCodexHostIdentity(), projectDeviceBindingApi.list(projectId),
  ])
  if (!currentRequest(sequence, projectId, userId)) return
  if (identityResult.status === 'fulfilled') host.value = identityResult.value
  else hostError.value = errorMessage(identityResult.reason, t('projectDeviceBinding.loadFailed'))
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

function selectDevice(event: Event) {
  selectedDeviceId.value = (event.target as HTMLSelectElement).value
  updateDirectory()
  error.value = ''
  message.value = ''
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
    const saved = await projectDeviceBindingApi.save(projectId, validated.deviceId, validated.projectDirectory)
    if (!currentRequest(sequence, projectId, userId)) return
    bindings.value = bindings.value.filter((binding) => binding.deviceId !== saved.deviceId).concat(saved)
    selectedDeviceId.value = saved.deviceId
    projectDirectory.value = saved.projectDirectory
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
    await projectDeviceBindingApi.delete(projectId, deviceId)
    if (!currentRequest(sequence, projectId, userId)) return
    bindings.value = bindings.value.filter((binding) => binding.deviceId !== deviceId)
    projectDirectory.value = ''
    message.value = t('projectDeviceBinding.deleted')
  } catch (reason) {
    if (currentRequest(sequence, projectId, userId)) error.value = errorMessage(reason, t('projectDeviceBinding.deleteFailed'))
  } finally {
    if (currentRequest(sequence, projectId, userId)) busy.value = false
  }
}

watch(() => [props.projectId, auth.currentUser?.id] as const, () => { void refresh() }, { immediate: true })
onUnmounted(() => { requestSequence++ })
</script>

<template>
  <section id="settings-device-binding" class="settings-section project-device-binding">
    <div class="binding-heading">
      <h2>{{ t('projectDeviceBinding.title') }}</h2>
      <button type="button" class="btn-secondary" :disabled="loading || busy" @click="refresh">{{ t('projectDeviceBinding.refresh') }}</button>
    </div>
    <p v-if="loading" class="binding-state" role="status">{{ t('projectDeviceBinding.loading') }}</p>
    <template v-else>
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
      </div>
    </template>
    <p v-if="hostError" class="feedback feedback--error" role="alert">{{ hostError }}</p>
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
</style>
