<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CheckCircle2, Download, LoaderCircle, MonitorCog, RefreshCw, TriangleAlert } from 'lucide-vue-next'
import {
  configurePiBridgeInstance,
  detectPiBridgePlatform,
  getPiBridgeHealth,
  listPiBridgeProjects,
  piBridgeInstallerUrl,
  savePiBridgeProject,
  type PiBridgePlatform,
} from '../services/piBridge'

const props = defineProps<{ projectId: number; projectName: string }>()
const loading = ref(false)
const saving = ref(false)
const installing = ref(false)
const bridgeAvailable = ref(false)
const bridgeStatus = ref('')
const directoryPath = ref('')
const error = ref('')
const message = ref('')
const platform = ref<PiBridgePlatform | null>(null)
let installTimer: number | null = null

const platformLabel = computed(() => platform.value === 'darwin' ? 'macOS' : platform.value === 'win32' ? 'Windows' : '当前系统')
const installerSupported = computed(() => platform.value === 'darwin' || platform.value === 'win32')
const installerUrl = computed(() => platform.value ? piBridgeInstallerUrl(platform.value) : '')

async function refresh() {
  loading.value = true
  error.value = ''
  message.value = ''
  platform.value = detectPiBridgePlatform()
  try {
    const health = await getPiBridgeHealth()
    bridgeAvailable.value = health != null
    bridgeStatus.value = health?.status ?? ''
    if (!health) return
    await configurePiBridgeInstance()
    const projects = await listPiBridgeProjects()
    const mapping = projects.find((item) => item.projectId === props.projectId)
    directoryPath.value = mapping?.directoryPath ?? ''
    if (mapping && !mapping.valid) error.value = mapping.error ?? '本地目录不可用'
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '读取 Bridge 配置失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  const value = directoryPath.value.trim()
  if (!value || saving.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await configurePiBridgeInstance()
    const saved = await savePiBridgeProject(props.projectId, props.projectName, value)
    directoryPath.value = saved.directoryPath
    message.value = '本地执行配置已保存'
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '保存本地执行配置失败'
  } finally {
    saving.value = false
  }
}

function beginInstallCheck() {
  installing.value = true
  if (installTimer != null) window.clearInterval(installTimer)
  installTimer = window.setInterval(async () => {
    if (!await getPiBridgeHealth()) return
    installing.value = false
    if (installTimer != null) window.clearInterval(installTimer)
    installTimer = null
    await refresh()
  }, 2000)
}

watch(() => props.projectId, () => { void refresh() }, { immediate: true })
onBeforeUnmount(() => { if (installTimer != null) window.clearInterval(installTimer) })
</script>

<template>
  <section id="settings-local-execution" class="local-execution-settings">
    <div class="section-header">
      <div><h2>本地执行</h2><p>连接本机 Pi，并为当前项目指定代码目录。</p></div>
      <button type="button" class="btn-secondary local-execution-action" :disabled="loading" @click="refresh">
        <LoaderCircle v-if="loading" class="button-spinner" aria-hidden="true" /><RefreshCw v-else aria-hidden="true" />检测
      </button>
    </div>

    <div v-if="loading" class="local-execution-state" aria-live="polite">
      <LoaderCircle class="button-spinner" aria-hidden="true" /><span>正在检测本机 Bridge…</span>
    </div>
    <div v-else-if="!bridgeAvailable" class="local-execution-state local-execution-state--warning">
      <TriangleAlert aria-hidden="true" />
      <div><strong>未检测到 Bridge</strong><p>下载 {{ platformLabel }} 安装包，完成系统安装后本页会识别 Bridge。</p></div>
      <a v-if="installerSupported" class="btn-primary local-execution-install" :href="installerUrl" download @click="beginInstallCheck">
        <Download aria-hidden="true" />安装 Bridge
      </a>
      <span v-else class="local-execution-unsupported">暂不支持该系统</span>
    </div>
    <template v-else>
      <div class="local-execution-state local-execution-state--ready">
        <CheckCircle2 aria-hidden="true" /><div><strong>Bridge 可用</strong><p>运行状态：{{ bridgeStatus || 'idle' }}</p></div>
      </div>
      <div class="local-execution-form">
        <label for="project-local-directory">本地项目目录</label>
        <div class="local-execution-controls">
          <input id="project-local-directory" v-model="directoryPath" type="text" class="input input--mono" placeholder="输入本地项目目录绝对路径" autocomplete="off" />
          <button type="button" class="btn-primary local-execution-action" :disabled="saving || !directoryPath.trim()" @click="save">
            <LoaderCircle v-if="saving" class="button-spinner" aria-hidden="true" /><MonitorCog v-else aria-hidden="true" />保存配置
          </button>
        </div>
        <p class="local-execution-hint">目录只保存在本机 Bridge，不会上传到 Linear Lite。</p>
      </div>
    </template>
    <p v-if="installing" class="feedback" role="status">等待 Bridge 安装并启动…</p>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <p v-if="message" class="feedback feedback--success" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.local-execution-settings { scroll-margin-top: 104px; min-width: 0; }
.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.section-header h2 { margin: 0; color: var(--color-text-primary); font-size: 16px; font-weight: var(--font-weight-semibold); }
.section-header p, .local-execution-state p, .local-execution-hint { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.5; }
.local-execution-action, .local-execution-install { display: inline-flex; align-items: center; justify-content: center; gap: 7px; }
.local-execution-action svg, .local-execution-install svg { width: 15px; height: 15px; }
.local-execution-state { min-height: 76px; margin-top: 18px; padding: 16px; display: flex; align-items: center; gap: 12px; border: 1px solid var(--color-border-subtle); border-radius: var(--radius-lg); background: var(--color-bg-secondary); }
.local-execution-state > svg { flex: 0 0 auto; width: 20px; height: 20px; color: var(--color-text-muted); }
.local-execution-state strong { color: var(--color-text-primary); font-size: 14px; font-weight: var(--font-weight-semibold); }
.local-execution-state--warning > svg { color: var(--color-status-warning); }
.local-execution-state--ready > svg { color: var(--color-success); }
.local-execution-install { margin-left: auto; min-height: 38px; padding: 0 14px; text-decoration: none; white-space: nowrap; }
.local-execution-unsupported { margin-left: auto; color: var(--color-text-muted); font-size: 13px; }
.local-execution-form { margin-top: 18px; }
.local-execution-form label { display: block; margin-bottom: 7px; color: var(--color-text-primary); font-size: 13px; font-weight: var(--font-weight-medium); }
.local-execution-controls { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; }
.local-execution-controls .btn-primary { min-width: 126px; }
.feedback { margin: 12px 0 0; padding: 10px 12px; border: 1px solid var(--color-border-subtle); border-radius: var(--radius-md); color: var(--color-text-secondary); background: var(--color-bg-secondary); font-size: 13px; }
.feedback--error { color: var(--project-settings-danger-text); border-color: var(--project-settings-danger-border); background: var(--project-settings-danger-bg); }
.feedback--success { color: var(--project-settings-success-text); border-color: color-mix(in srgb, var(--project-settings-success-text) 20%, transparent); background: var(--project-settings-success-bg); }
@media (max-width: 700px) {
  .local-execution-state { align-items: flex-start; flex-wrap: wrap; }
  .local-execution-install, .local-execution-unsupported { width: 100%; margin-left: 32px; }
  .local-execution-controls { grid-template-columns: 1fr; }
}
</style>
