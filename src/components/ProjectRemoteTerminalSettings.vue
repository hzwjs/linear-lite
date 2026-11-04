<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { LoaderCircle, Plus, RefreshCw, ServerCog, Trash2 } from 'lucide-vue-next'
import {
  remoteTerminalApi,
  type RtAuditRecord,
  type RtCertificateRecord,
  type RtOverview,
  type RtProfile,
  type RtProfileInput,
  type RtTarget,
  type RtTargetInput,
} from '../services/api/remoteTerminal'
import { projectApi } from '../services/api/project'
import { copyTextToClipboard } from '../utils/clipboard'
import type { User } from '../types/domain'

const props = defineProps<{ projectId: number }>()
const { t } = useI18n()

const overview = ref<RtOverview | null>(null)
const members = ref<User[]>([])
const certificates = ref<RtCertificateRecord[]>([])
const auditEvents = ref<RtAuditRecord[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const script = ref<{ targetId: string; text: string } | null>(null)
const showRecords = ref(false)

const emptyTarget = (): RtTargetInput => ({ targetId: '', alias: '', environment: '', host: '', port: 22, hostKey: '' })
const emptyProfile = (): RtProfileInput => ({ profileId: '', loginUser: '', maxTtlSeconds: 3600, permitPty: true })
const targetForm = ref<RtTargetInput>(emptyTarget())
const editingTargetRef = ref<number | null>(null)
const profileForms = ref<Record<number, RtProfileInput>>({})
const memberName = computed(() => new Map(members.value.map((member) => [member.id, member.username])))
const hasActiveCa = computed(() => overview.value?.cas.some((ca) => ca.status === 'active') ?? false)
const retiringCa = computed(() => overview.value?.cas.find((ca) => ca.status === 'retiring') ?? null)
let requestSeq = 0

function fail(reason: unknown, fallback: string) {
  error.value = reason instanceof Error && reason.message ? reason.message : fallback
}

async function load() {
  const seq = ++requestSeq
  const projectId = props.projectId
  loading.value = true
  error.value = ''
  try {
    const [loaded, projectMembers] = await Promise.all([remoteTerminalApi.overview(projectId), projectApi.listMembers(projectId)])
    if (seq !== requestSeq) return
    overview.value = loaded
    members.value = projectMembers
  } catch (reason) {
    if (seq === requestSeq) fail(reason, t('remoteTerminal.errors.loadFailed'))
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

async function run(action: () => Promise<unknown>, success: string, failure: string) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
    message.value = success
    await load()
  } catch (reason) {
    fail(reason, failure)
  } finally {
    busy.value = false
  }
}

const createCa = () => run(() => remoteTerminalApi.createCa(props.projectId), t('remoteTerminal.ca.created'), t('remoteTerminal.errors.actionFailed'))

function rotateCa() {
  if (!window.confirm(t('remoteTerminal.ca.rotateConfirm'))) return
  return run(() => remoteTerminalApi.rotateCa(props.projectId), t('remoteTerminal.ca.rotated'), t('remoteTerminal.errors.actionFailed'))
}

function retireCa(caId: number) {
  if (!window.confirm(t('remoteTerminal.ca.retireConfirm'))) return
  return run(() => remoteTerminalApi.retireCa(props.projectId, caId), t('remoteTerminal.ca.retired'), t('remoteTerminal.errors.actionFailed'))
}

function editTarget(target: RtTarget) {
  editingTargetRef.value = target.id
  targetForm.value = { targetId: target.targetId, alias: target.alias, environment: target.environment, host: target.host, port: target.port, hostKey: target.hostKey }
}

function cancelEdit() {
  editingTargetRef.value = null
  targetForm.value = emptyTarget()
}

function saveTarget() {
  const input = { ...targetForm.value, targetId: targetForm.value.targetId.trim(), hostKey: targetForm.value.hostKey.trim() }
  const ref = editingTargetRef.value
  return run(async () => {
    if (ref === null) await remoteTerminalApi.createTarget(props.projectId, input)
    else await remoteTerminalApi.updateTarget(props.projectId, ref, input)
    cancelEdit()
  }, t('remoteTerminal.target.saved'), t('remoteTerminal.errors.actionFailed'))
}

function deleteTarget(target: RtTarget) {
  if (!window.confirm(t('remoteTerminal.target.deleteConfirm', { name: target.alias }))) return
  return run(() => remoteTerminalApi.deleteTarget(props.projectId, target.id), t('remoteTerminal.target.deleted'), t('remoteTerminal.errors.actionFailed'))
}

function profileForm(target: RtTarget): RtProfileInput {
  return (profileForms.value[target.id] ??= emptyProfile())
}

function addProfile(target: RtTarget) {
  const input = { ...profileForm(target) }
  return run(async () => {
    await remoteTerminalApi.createProfile(props.projectId, target.id, input)
    profileForms.value[target.id] = emptyProfile()
  }, t('remoteTerminal.profile.saved'), t('remoteTerminal.errors.actionFailed'))
}

function deleteProfile(profile: RtProfile) {
  if (!window.confirm(t('remoteTerminal.profile.deleteConfirm', { name: profile.loginUser }))) return
  return run(() => remoteTerminalApi.deleteProfile(props.projectId, profile.id), t('remoteTerminal.profile.deleted'), t('remoteTerminal.errors.actionFailed'))
}

function toggleGrant(profile: RtProfile, userId: number, granted: boolean) {
  const next = granted ? [...new Set([...profile.grantUserIds, userId])] : profile.grantUserIds.filter((id) => id !== userId)
  return run(() => remoteTerminalApi.setGrants(props.projectId, profile.id, next), t('remoteTerminal.grants.saved'), t('remoteTerminal.errors.actionFailed'))
}

async function showScript(target: RtTarget) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    script.value = { targetId: target.targetId, text: await remoteTerminalApi.accessScript(props.projectId, target.id) }
  } catch (reason) {
    fail(reason, t('remoteTerminal.errors.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function copyScript() {
  if (!script.value) return
  const copied = await copyTextToClipboard(script.value.text)
  message.value = copied ? t('remoteTerminal.script.copied') : t('remoteTerminal.script.copyFailed')
}

async function toggleRecords() {
  showRecords.value = !showRecords.value
  if (!showRecords.value) return
  try {
    const [issued, events] = await Promise.all([remoteTerminalApi.certificates(props.projectId), remoteTerminalApi.auditEvents(props.projectId)])
    certificates.value = issued
    auditEvents.value = events
  } catch (reason) {
    fail(reason, t('remoteTerminal.errors.loadFailed'))
  }
}

const userLabel = (userId: number) => memberName.value.get(userId) ?? `#${userId}`

watch(() => props.projectId, () => {
  overview.value = null
  script.value = null
  showRecords.value = false
  cancelEdit()
  void load()
}, { immediate: true })

// 脚本只是公开的 CA 公钥与 principal 名单，但离开页面后不再保留。
onBeforeUnmount(() => { requestSeq += 1; script.value = null })
</script>

<template>
  <div id="settings-remote-terminal" class="settings-cluster remote-terminal" data-testid="remote-terminal-settings">
    <div class="cluster-header">
      <div class="title-row">
        <ServerCog aria-hidden="true" />
        <h2>{{ t('remoteTerminal.title') }}</h2>
        <button type="button" class="btn-secondary icon-button" :disabled="loading || busy" :aria-label="t('remoteTerminal.refresh')" @click="load">
          <LoaderCircle v-if="loading" class="button-spinner" aria-hidden="true" /><RefreshCw v-else aria-hidden="true" />
        </button>
      </div>
      <p>{{ t('remoteTerminal.description') }}</p>
    </div>

    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <p v-if="message" class="feedback feedback--success" role="status">{{ message }}</p>
    <p v-if="overview && !overview.enabled" class="feedback feedback--error" data-testid="remote-terminal-disabled">{{ t('remoteTerminal.disabled') }}</p>

    <template v-if="overview">
      <section class="settings-section">
        <div class="section-header"><h3>{{ t('remoteTerminal.ca.title') }}</h3><p>{{ t('remoteTerminal.ca.description') }}</p></div>
        <ul v-if="overview.cas.length" class="plain-list">
          <li v-for="ca in overview.cas" :key="ca.id" :data-testid="`remote-terminal-ca-${ca.id}`">
            <strong>{{ t(`remoteTerminal.ca.status.${ca.status}`) }}</strong>
            <code>{{ ca.fingerprint }}</code>
            <button v-if="ca.status === 'retiring'" type="button" class="btn-secondary" :disabled="busy" @click="retireCa(ca.id)">{{ t('remoteTerminal.ca.retire') }}</button>
          </li>
        </ul>
        <div class="actions">
          <button v-if="!hasActiveCa" type="button" class="btn-primary" data-testid="remote-terminal-create-ca" :disabled="busy || !overview.enabled" @click="createCa">{{ t('remoteTerminal.ca.create') }}</button>
          <button v-else type="button" class="btn-secondary" data-testid="remote-terminal-rotate-ca" :disabled="busy || !overview.enabled || !!retiringCa" @click="rotateCa">{{ t('remoteTerminal.ca.rotate') }}</button>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-header"><h3>{{ t('remoteTerminal.target.title') }}</h3><p>{{ t('remoteTerminal.target.description') }}</p></div>

        <article v-for="target in overview.targets" :key="target.id" class="target" :data-testid="`remote-terminal-target-${target.targetId}`">
          <header>
            <div>
              <strong>{{ target.alias }}</strong> <span class="muted">{{ target.environment }}</span>
              <p class="muted"><code>{{ target.targetId }}</code> · {{ target.host }}:{{ target.port }} · {{ t('remoteTerminal.target.fingerprint') }} <code>{{ target.hostKeyFingerprint }}</code></p>
            </div>
            <div class="actions">
              <button type="button" class="btn-secondary" :disabled="busy" @click="editTarget(target)">{{ t('remoteTerminal.target.edit') }}</button>
              <button type="button" class="btn-secondary" :disabled="busy || !target.profiles.length || !hasActiveCa" @click="showScript(target)">{{ t('remoteTerminal.script.show') }}</button>
              <button type="button" class="btn-secondary danger" :disabled="busy" :aria-label="t('remoteTerminal.target.delete')" @click="deleteTarget(target)"><Trash2 aria-hidden="true" /></button>
            </div>
          </header>

          <div v-for="profile in target.profiles" :key="profile.id" class="profile">
            <div class="profile-head">
              <span><code>{{ profile.loginUser }}</code> · ≤ {{ profile.maxTtlSeconds }}s<template v-if="!profile.permitPty"> · {{ t('remoteTerminal.profile.noPty') }}</template></span>
              <button type="button" class="btn-secondary danger" :disabled="busy" :aria-label="t('remoteTerminal.profile.delete')" @click="deleteProfile(profile)"><Trash2 aria-hidden="true" /></button>
            </div>
            <div class="grants" role="group" :aria-label="t('remoteTerminal.grants.label')">
              <label v-for="member in members" :key="member.id" class="grant">
                <input type="checkbox" :checked="profile.grantUserIds.includes(member.id)" :disabled="busy" @change="toggleGrant(profile, member.id, ($event.target as HTMLInputElement).checked)" />
                {{ member.username }}
              </label>
            </div>
          </div>

          <form class="inline-form" @submit.prevent="addProfile(target)">
            <input v-model="profileForm(target).profileId" class="input" :placeholder="t('remoteTerminal.profile.idPlaceholder')" maxlength="100" required />
            <input v-model="profileForm(target).loginUser" class="input" :placeholder="t('remoteTerminal.profile.userPlaceholder')" maxlength="32" required />
            <input v-model.number="profileForm(target).maxTtlSeconds" class="input narrow" type="number" min="1" max="28800" :aria-label="t('remoteTerminal.profile.ttl')" />
            <label class="grant"><input v-model="profileForm(target).permitPty" type="checkbox" /> PTY</label>
            <button type="submit" class="btn-secondary" :disabled="busy"><Plus aria-hidden="true" />{{ t('remoteTerminal.profile.add') }}</button>
          </form>
        </article>

        <form class="target-form" data-testid="remote-terminal-target-form" @submit.prevent="saveTarget">
          <h4>{{ editingTargetRef === null ? t('remoteTerminal.target.add') : t('remoteTerminal.target.editing') }}</h4>
          <div class="grid">
            <input v-model="targetForm.targetId" class="input input--mono" :placeholder="t('remoteTerminal.target.idPlaceholder')" maxlength="100" :disabled="editingTargetRef !== null" required />
            <input v-model="targetForm.alias" class="input" :placeholder="t('remoteTerminal.target.aliasPlaceholder')" maxlength="256" required />
            <input v-model="targetForm.environment" class="input" :placeholder="t('remoteTerminal.target.environmentPlaceholder')" maxlength="256" required />
            <input v-model="targetForm.host" class="input" :placeholder="t('remoteTerminal.target.hostPlaceholder')" maxlength="256" required />
            <input v-model.number="targetForm.port" class="input narrow" type="number" min="1" max="65535" :aria-label="t('remoteTerminal.target.port')" />
          </div>
          <textarea v-model="targetForm.hostKey" class="input input--mono" rows="2" :placeholder="t('remoteTerminal.target.hostKeyPlaceholder')" required />
          <p class="muted">{{ t('remoteTerminal.target.hostKeyHint') }}</p>
          <div class="actions">
            <button type="submit" class="btn-primary" :disabled="busy">{{ t('remoteTerminal.target.save') }}</button>
            <button v-if="editingTargetRef !== null" type="button" class="btn-secondary" :disabled="busy" @click="cancelEdit">{{ t('remoteTerminal.target.cancel') }}</button>
          </div>
        </form>
      </section>

      <section v-if="script" class="settings-section" data-testid="remote-terminal-script">
        <div class="section-header"><h3>{{ t('remoteTerminal.script.title', { target: script.targetId }) }}</h3><p>{{ t('remoteTerminal.script.description') }}</p></div>
        <pre class="script">{{ script.text }}</pre>
        <div class="actions"><button type="button" class="btn-secondary" @click="copyScript">{{ t('remoteTerminal.script.copy') }}</button></div>
      </section>

      <section class="settings-section">
        <div class="section-header">
          <h3>{{ t('remoteTerminal.records.title') }}</h3>
          <button type="button" class="btn-secondary" @click="toggleRecords">{{ showRecords ? t('remoteTerminal.records.hide') : t('remoteTerminal.records.show') }}</button>
        </div>
        <template v-if="showRecords">
          <h4>{{ t('remoteTerminal.records.certificates') }}</h4>
          <p v-if="!certificates.length" class="muted">{{ t('remoteTerminal.records.empty') }}</p>
          <ul v-else class="plain-list" data-testid="remote-terminal-certificates">
            <li v-for="row in certificates" :key="row.serial">
              <span>#{{ row.serial }} · {{ userLabel(row.userId) }} → <code>{{ row.principal }}</code></span>
              <span class="muted">{{ row.createdAt }} · {{ row.clientIp }}</span>
            </li>
          </ul>
          <h4>{{ t('remoteTerminal.records.changes') }}</h4>
          <ul class="plain-list" data-testid="remote-terminal-audit">
            <li v-for="event in auditEvents" :key="event.id">
              <span>{{ userLabel(event.actorId) }} · <code>{{ event.action }}</code> · {{ event.subject }}</span>
              <span class="muted">{{ event.createdAt }}</span>
            </li>
          </ul>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.remote-terminal { scroll-margin-top: 104px; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.cluster-header h2 { margin: 0; color: var(--color-text-primary); font-size: 16px; font-weight: var(--font-weight-semibold); }
.cluster-header p, .section-header p, .muted { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.5; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title-row svg { width: 18px; height: 18px; }
.icon-button { margin-left: auto; padding: 6px 8px; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.section-header h3, h4 { margin: 0; color: var(--color-text-primary); font-size: 14px; font-weight: var(--font-weight-semibold); }
h4 { margin-top: 14px; font-size: 13px; }
.plain-list { list-style: none; margin: 10px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.plain-list li { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: space-between; font-size: 13px; }
.actions { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.target { margin-top: 14px; padding: 14px; border: 1px solid var(--color-border-subtle); border-radius: var(--radius-lg); background: var(--color-bg-secondary); }
.target > header { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.profile { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--color-border-subtle); }
.profile-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px; }
.grants { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 8px; }
.grant { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; }
.inline-form { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; align-items: center; }
.inline-form .input { min-width: 120px; flex: 1 1 140px; }
.narrow { max-width: 110px; flex: 0 0 110px !important; }
.target-form { margin-top: 18px; display: flex; flex-direction: column; gap: 8px; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px; }
.script { margin: 10px 0 0; max-height: 320px; overflow: auto; padding: 12px; border-radius: var(--radius-md); background: var(--color-bg-secondary); font-size: 12px; white-space: pre; }
.danger { color: var(--project-settings-danger-text); }
.feedback { margin: 0; padding: 10px 12px; border: 1px solid var(--color-border-subtle); border-radius: var(--radius-md); font-size: 13px; }
.feedback--error { color: var(--project-settings-danger-text); border-color: var(--project-settings-danger-border); background: var(--project-settings-danger-bg); }
.feedback--success { color: var(--project-settings-success-text); background: var(--project-settings-success-bg); }
code { font-size: 12px; word-break: break-all; }
</style>
