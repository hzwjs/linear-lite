<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { Bot, Check, Clipboard, LockKeyhole, Radio, Terminal } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'

const MCP_ENDPOINT = 'https://linearlite.eobua.cn/mcp'
const CONNECTOR_RELEASES_URL = 'https://github.com/hzwjs/linear-lite/releases'
const { t } = useI18n()

type ConnectionMode = 'remote' | 'local'
type LocalTarget = 'vscode' | 'generic'
type Platform = 'macos' | 'windows' | 'linux'

const mode = ref<ConnectionMode>('remote')
const localTarget = ref<LocalTarget>('vscode')
const executablePath = ref('')
const copied = ref(false)
const copyError = ref(false)
const platform = ref<Platform>(detectPlatform())
let copyFeedbackTimer: ReturnType<typeof setTimeout> | undefined

const executablePlaceholder = computed(() => {
  if (platform.value === 'windows') return t('agentSetup.commandPlaceholderWindows')
  if (platform.value === 'linux') return t('agentSetup.commandPlaceholderLinux')
  return t('agentSetup.commandPlaceholderMac')
})

const configText = computed(() => {
  if (mode.value === 'remote') {
    return JSON.stringify({
      servers: {
        'linear-lite': {
          type: 'http',
          url: MCP_ENDPOINT
        }
      }
    }, null, 2)
  }

  const command = executablePath.value.trim()
  if (!command) return ''
  if (localTarget.value === 'vscode') {
    return JSON.stringify({
      servers: {
        'linear-lite': {
          type: 'stdio',
          command,
          args: []
        }
      }
    }, null, 2)
  }
  return JSON.stringify({ command, args: [] }, null, 2)
})

const canCopy = computed(() => configText.value.length > 0)

function detectPlatform(): Platform {
  const agent = navigator.userAgent.toLowerCase()
  if (agent.includes('windows')) return 'windows'
  if (agent.includes('linux')) return 'linux'
  return 'macos'
}

async function copyConfig() {
  if (!canCopy.value) return
  copyError.value = false
  copied.value = false
  try {
    await navigator.clipboard.writeText(configText.value)
    copied.value = true
  } catch {
    copyError.value = true
  }
  if (copyFeedbackTimer) clearTimeout(copyFeedbackTimer)
  copyFeedbackTimer = setTimeout(() => {
    copied.value = false
    copyError.value = false
  }, 2400)
}

onUnmounted(() => {
  if (copyFeedbackTimer) clearTimeout(copyFeedbackTimer)
})
</script>

<template>
  <main class="agent-setup" id="agent-setup-main">
    <header class="agent-setup__header">
      <div class="agent-setup__heading">
        <span class="agent-setup__product">Linear Lite</span>
        <h1>{{ t('agentSetup.title') }}</h1>
        <p>{{ t('agentSetup.purpose') }}</p>
      </div>
      <div class="agent-setup__codex-note">
        <LockKeyhole :size="15" aria-hidden="true" />
        <span>{{ t('agentSetup.codexNote') }}</span>
      </div>
    </header>

    <section class="agent-setup__endpoint" aria-label="MCP endpoint">
        <div class="agent-setup__endpoint-main">
          <span class="agent-setup__endpoint-icon"><Radio :size="16" aria-hidden="true" /></span>
          <div>
            <span class="agent-setup__eyebrow">{{ t('agentSetup.endpointLabel') }}</span>
            <code>{{ MCP_ENDPOINT }}</code>
          </div>
        </div>
        <span class="agent-setup__scope">{{ t('agentSetup.readWriteScopes') }}</span>
    </section>

      <div class="agent-setup__workspace">
        <nav class="agent-setup__modes" :aria-label="t('agentSetup.title')">
          <button
            type="button"
            class="agent-setup__mode"
            :class="{ 'agent-setup__mode--active': mode === 'remote' }"
            :aria-pressed="mode === 'remote'"
            @click="mode = 'remote'"
          >
            <Radio :size="17" aria-hidden="true" />
            <span>{{ t('agentSetup.remote') }}</span>
          </button>
          <button
            type="button"
            class="agent-setup__mode"
            :class="{ 'agent-setup__mode--active': mode === 'local' }"
            :aria-pressed="mode === 'local'"
            @click="mode = 'local'"
          >
            <Terminal :size="17" aria-hidden="true" />
            <span>{{ t('agentSetup.local') }}</span>
          </button>
        </nav>

        <section class="agent-setup__instructions" :aria-labelledby="`agent-setup-${mode}-title`">
          <div class="agent-setup__instructions-header">
            <div class="agent-setup__instructions-title">
              <span class="agent-setup__large-icon">
                <Bot v-if="mode === 'remote'" :size="20" aria-hidden="true" />
                <Terminal v-else :size="20" aria-hidden="true" />
              </span>
              <div>
                <h2 :id="`agent-setup-${mode}-title`">{{ t(`agentSetup.${mode}`) }}</h2>
                <p>{{ t(`agentSetup.${mode}Description`) }}</p>
              </div>
            </div>

            <label v-if="mode === 'local'" class="agent-setup__target-control">
              <span>{{ t('agentSetup.agentLabel') }}</span>
              <select v-model="localTarget">
                <option value="vscode">{{ t('agentSetup.vsCode') }}</option>
                <option value="generic">{{ t('agentSetup.genericStdio') }}</option>
              </select>
            </label>
          </div>

          <div v-if="mode === 'remote'" class="agent-setup__detail-line">
            <span class="agent-setup__detail-mark" aria-hidden="true">VS</span>
            <p>{{ t('agentSetup.remoteOAuthNote') }}</p>
          </div>
          <div v-else class="agent-setup__local-fields">
            <label class="agent-setup__path-field">
              <span>{{ t('agentSetup.commandLabel') }}</span>
              <input
                v-model="executablePath"
                type="text"
                :placeholder="executablePlaceholder"
                autocomplete="off"
                spellcheck="false"
                aria-describedby="agent-setup-path-help"
              />
            </label>
            <label class="agent-setup__platform-field">
              <span>OS</span>
              <select v-model="platform">
                <option value="macos">macOS</option>
                <option value="windows">Windows</option>
                <option value="linux">Linux</option>
              </select>
            </label>
            <p id="agent-setup-path-help" class="agent-setup__help">{{ t('agentSetup.commandHelp') }}</p>
            <a
              class="agent-setup__download"
              :href="CONNECTOR_RELEASES_URL"
              target="_blank"
              rel="noreferrer"
            >
              {{ t('agentSetup.downloadConnector') }}
            </a>
            <p v-if="localTarget === 'generic'" class="agent-setup__help">
              {{ t('agentSetup.genericStdioNote') }}
            </p>
            <div class="agent-setup__detail-line agent-setup__detail-line--local">
              <span class="agent-setup__detail-mark agent-setup__detail-mark--terminal" aria-hidden="true">
                <Terminal :size="14" />
              </span>
              <p>{{ t('agentSetup.localOAuthNote') }}</p>
            </div>
          </div>

          <div class="agent-setup__config-heading">
            <span>{{ t('agentSetup.configLabel') }}</span>
            <button
              type="button"
              class="agent-setup__copy"
              :disabled="!canCopy"
              @click="copyConfig"
            >
              <Check v-if="copied" :size="15" aria-hidden="true" />
              <Clipboard v-else :size="15" aria-hidden="true" />
              {{ copied ? t('agentSetup.copied') : t('agentSetup.copyConfig') }}
            </button>
          </div>
          <pre class="agent-setup__code" :class="{ 'agent-setup__code--empty': !configText }"><code>{{ configText || '{ }' }}</code></pre>
          <p v-if="copyError" class="agent-setup__copy-error" role="alert">{{ t('agentSetup.copyFailed') }}</p>
          <p class="agent-setup__credentials-note"><LockKeyhole :size="14" aria-hidden="true" />{{ t('agentSetup.credentialsNote') }}</p>
        </section>
    </div>
  </main>
</template>

<style scoped>
.agent-setup {
  --setup-accent: var(--color-accent, #475569);
  --setup-muted-bg: var(--color-bg-muted, #f6f7f8);
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 34px clamp(22px, 4vw, 56px) 64px;
  color: var(--color-text-primary);
  background: var(--color-bg-base);
}

.agent-setup__header,
.agent-setup__endpoint,
.agent-setup__workspace {
  width: min(100%, 1040px);
  margin-inline: auto;
}

.agent-setup__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--color-border);
}

.agent-setup__heading { min-width: 0; }
.agent-setup__product {
  display: block;
  margin-bottom: 9px;
  color: var(--color-text-muted);
  font-size: 11px;
  letter-spacing: 0.01em;
}
.agent-setup__heading h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.035em;
}
.agent-setup__heading p {
  max-width: 66ch;
  margin: 7px 0 0;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.55;
}
.agent-setup__download {
  display: inline-flex;
  margin-top: 2px;
  color: var(--color-accent, #475569);
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.agent-setup__download:focus-visible {
  outline: 2px solid var(--color-accent, #475569);
  outline-offset: 3px;
}
.agent-setup__codex-note {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm, 6px);
  color: var(--color-text-secondary);
  font-size: 11px;
}
.agent-setup__codex-note svg { color: var(--color-text-muted); }

.agent-setup__large-icon,
.agent-setup__endpoint-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  color: var(--setup-accent);
}
.agent-setup__endpoint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 0;
  border-bottom: 1px solid var(--color-border);
}
.agent-setup__endpoint-main { display: flex; align-items: center; gap: 12px; min-width: 0; }
.agent-setup__endpoint-icon { width: 32px; height: 32px; border: 1px solid var(--color-border); border-radius: 8px; }
.agent-setup__endpoint-main > div { display: grid; gap: 4px; min-width: 0; }
.agent-setup__eyebrow { color: var(--color-text-muted); font-size: 11px; }
.agent-setup__endpoint code { overflow-wrap: anywhere; color: var(--color-text-primary); font: 12px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; }
.agent-setup__scope { max-width: 38ch; color: var(--color-text-muted); font-size: 11px; text-align: right; }

.agent-setup__workspace { display: grid; grid-template-columns: 184px minmax(0, 1fr); gap: 38px; padding-top: 28px; }
.agent-setup__modes { display: grid; align-content: start; gap: 5px; }
.agent-setup__mode {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: var(--border-radius-sm, 6px);
  color: var(--color-text-secondary);
  background: transparent;
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.agent-setup__mode:hover { color: var(--color-text-primary); background: var(--setup-muted-bg); }
.agent-setup__mode--active {
  border-color: color-mix(in srgb, var(--setup-accent) 22%, var(--color-border));
  color: var(--color-text-primary);
  background: color-mix(in srgb, var(--setup-accent) 6%, var(--color-bg-base));
}
.agent-setup__mode--active svg { color: var(--setup-accent); }

.agent-setup__instructions { min-width: 0; }
.agent-setup__instructions-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--color-border);
}
.agent-setup__instructions-title { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.agent-setup__large-icon { width: 34px; height: 34px; border: 1px solid var(--color-border); border-radius: 8px; }
.agent-setup__instructions-title h2 { margin: 1px 0 5px; font-size: 15px; font-weight: 620; }
.agent-setup__instructions-title p { max-width: 62ch; margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; }
.agent-setup__target-control,
.agent-setup__platform-field { display: grid; gap: 5px; flex: 0 0 auto; }
.agent-setup__target-control > span,
.agent-setup__platform-field > span,
.agent-setup__path-field > span { color: var(--color-text-secondary); font-size: 11px; }
.agent-setup__target-control select,
.agent-setup__platform-field select,
.agent-setup__path-field input {
  min-height: 34px;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm, 6px);
  color: var(--color-text-primary);
  background: var(--color-bg-base);
  font: inherit;
  font-size: 12px;
}
.agent-setup__target-control select,
.agent-setup__platform-field select { padding: 0 27px 0 9px; }
.agent-setup__path-field input { width: 100%; padding: 0 10px; }
.agent-setup__target-control select:focus-visible,
.agent-setup__platform-field select:focus-visible,
.agent-setup__path-field input:focus-visible,
.agent-setup__mode:focus-visible,
.agent-setup__copy:focus-visible { outline: 2px solid var(--setup-accent); outline-offset: 2px; }

.agent-setup__detail-line { display: flex; align-items: flex-start; gap: 9px; margin: 18px 0; }
.agent-setup__detail-line p { max-width: 68ch; margin: 1px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; }
.agent-setup__detail-mark { display: grid; flex: 0 0 auto; width: 22px; height: 22px; place-items: center; border-radius: 5px; color: var(--setup-accent); background: color-mix(in srgb, var(--setup-accent) 9%, transparent); font-size: 9px; font-weight: 700; }
.agent-setup__detail-mark--terminal { color: var(--color-text-secondary); background: var(--setup-muted-bg); }
.agent-setup__local-fields { display: grid; grid-template-columns: minmax(0, 1fr) 136px; align-items: end; gap: 8px 14px; margin: 18px 0 20px; }
.agent-setup__path-field { display: grid; min-width: 0; gap: 6px; }
.agent-setup__help { grid-column: 1 / -1; margin: 0; color: var(--color-text-muted); font-size: 11px; line-height: 1.5; }
.agent-setup__detail-line--local { grid-column: 1 / -1; margin: 8px 0 0; }

.agent-setup__config-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 21px; color: var(--color-text-secondary); font-size: 11px; }
.agent-setup__copy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--border-radius-sm, 6px);
  color: var(--color-text-primary);
  background: var(--color-bg-base);
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.agent-setup__copy:hover:not(:disabled) { background: var(--setup-muted-bg); }
.agent-setup__copy:disabled { color: var(--color-text-muted); cursor: not-allowed; }
.agent-setup__copy svg { color: var(--setup-accent); }
.agent-setup__code {
  overflow: auto;
  min-height: 124px;
  margin: 9px 0 0;
  padding: 15px 16px;
  border: 1px solid #303640;
  border-radius: 8px;
  color: #e9edf2;
  background: #171a1f;
  font: 12px/1.65 ui-monospace, SFMono-Regular, Menlo, monospace;
  tab-size: 2;
}
.agent-setup__code--empty { color: #9098a3; }
.agent-setup__copy-error { margin: 7px 0 0; color: var(--color-danger, #b42318); font-size: 11px; }
.agent-setup__credentials-note { display: flex; align-items: center; gap: 7px; margin: 12px 0 0; color: var(--color-text-muted); font-size: 11px; line-height: 1.5; }
.agent-setup__credentials-note svg { flex: 0 0 auto; }

@media (max-width: 760px) {
  .agent-setup { padding: 25px 18px 48px; }
  .agent-setup__header { align-items: flex-start; flex-direction: column; gap: 14px; }
  .agent-setup__codex-note { white-space: normal; }
  .agent-setup__endpoint { align-items: flex-start; flex-direction: column; gap: 10px; }
  .agent-setup__scope { text-align: left; }
  .agent-setup__workspace { grid-template-columns: 1fr; gap: 20px; padding-top: 20px; }
  .agent-setup__modes { display: flex; border-bottom: 1px solid var(--color-border); }
  .agent-setup__mode { min-height: 36px; }
  .agent-setup__instructions-header { align-items: flex-start; flex-direction: column; }
  .agent-setup__target-control { width: 100%; }
  .agent-setup__target-control select { width: 100%; }
}

@media (max-width: 480px) {
  .agent-setup__heading h1 { font-size: 21px; }
  .agent-setup__local-fields { grid-template-columns: 1fr; }
  .agent-setup__platform-field,
  .agent-setup__help,
  .agent-setup__detail-line--local { grid-column: 1; }
  .agent-setup__platform-field select { width: 100%; }
  .agent-setup__config-heading { align-items: flex-start; }
  .agent-setup__code { padding: 12px; font-size: 11px; }
}
</style>
