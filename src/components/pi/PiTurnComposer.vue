<script setup lang="ts">
import { ArrowLeft, ArrowUp, Check, ChevronDown, ChevronRight, CircleX, Loader2 } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref } from 'vue'
import type { PiSettingsModel, PiSettingsState } from '../../services/api/agent'

const props = defineProps<{ modelValue: string; active: boolean; canSubmit: boolean; submitting: boolean; canceling: boolean; settings: PiSettingsState | null; settingsLoading: boolean; settingsEnabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: []; stop: []; readSettings: []; setModel: [model: PiSettingsModel]; setThinkingLevel: [level: string] }>()
type SettingsMenu = 'closed' | 'root' | 'model' | 'thinking'
const settingsMenu = ref<SettingsMenu>('closed')
const composer = ref<HTMLElement | null>(null)
const thinkingLabels: Record<string, string> = { off: '关闭', minimal: '极低', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最大' }
const thinkingLabel = computed(() => props.settings ? (thinkingLabels[props.settings.current.thinkingLevel] ?? props.settings.current.thinkingLevel) : '')
const modelLabel = computed(() => props.settings?.current.model.label ?? '')
const groupedModels = computed(() => {
  const groups = new Map<string, PiSettingsModel[]>()
  for (const model of props.settings?.models ?? []) groups.set(model.provider, [...(groups.get(model.provider) ?? []), model])
  return [...groups.entries()]
})
function updatePrompt(event: Event) { emit('update:modelValue', (event.target as HTMLTextAreaElement).value) }
function triggerPrimaryAction() { if (!props.submitting && !props.canceling) { if (props.active) emit('stop'); else if (props.canSubmit && props.modelValue.trim()) emit('submit') } }
function submitTurn() { if (!props.submitting && !props.canceling && props.canSubmit && props.modelValue.trim()) emit('submit') }
function toggleSettings() {
  if (!props.settingsEnabled || props.settingsLoading) return
  if (settingsMenu.value === 'closed') {
    settingsMenu.value = 'root'
    if (!props.settings) emit('readSettings')
  } else settingsMenu.value = 'closed'
}
function chooseModel(model: PiSettingsModel) {
  if (!props.settingsLoading && props.settingsEnabled) { settingsMenu.value = 'closed'; emit('setModel', model) }
}
function chooseThinkingLevel(level: string) {
  if (!props.settingsLoading && props.settingsEnabled) { settingsMenu.value = 'closed'; emit('setThinkingLevel', level) }
}
function closeOnOutsideClick(event: MouseEvent) { if (!composer.value?.contains(event.target as Node)) settingsMenu.value = 'closed' }
document.addEventListener('click', closeOnOutsideClick)
onBeforeUnmount(() => document.removeEventListener('click', closeOnOutsideClick))
</script>

<template>
  <div ref="composer" class="pi-turn-composer">
    <!-- 菜单是输入框的内部浮层；截断内部点击，避免被全局 outside-click 监听误判为关闭。 -->
    <div v-if="settingsMenu !== 'closed'" class="pi-settings-menu" role="menu" aria-label="Pi 模型设置" @click.stop>
      <template v-if="settingsMenu === 'root'">
        <button type="button" class="pi-settings-menu__row" :disabled="settingsLoading" @click="settingsMenu = 'model'"><span>模型</span><span class="pi-settings-menu__value">{{ modelLabel || '读取中' }}</span><ChevronRight aria-hidden="true" /></button>
        <button type="button" class="pi-settings-menu__row" :disabled="settingsLoading" @click="settingsMenu = 'thinking'"><span>推理强度</span><span class="pi-settings-menu__value">{{ thinkingLabel || '读取中' }}</span><ChevronRight aria-hidden="true" /></button>
      </template>
      <template v-else-if="settingsMenu === 'model'">
        <button type="button" class="pi-settings-menu__back" @click="settingsMenu = 'root'"><ArrowLeft aria-hidden="true" />模型</button>
        <div v-for="[provider, models] in groupedModels" :key="provider" class="pi-settings-menu__group"><span class="pi-settings-menu__group-label">{{ provider }}</span>
          <button v-for="model in models" :key="`${model.provider}/${model.modelId}`" type="button" class="pi-settings-menu__option" :disabled="settingsLoading" :title="`${model.provider} / ${model.modelId}`" @click="chooseModel(model)"><span>{{ model.label }}</span><Check v-if="model.provider === settings?.current.model.provider && model.modelId === settings?.current.model.modelId" aria-label="当前模型" /></button>
        </div>
      </template>
      <template v-else>
        <button type="button" class="pi-settings-menu__back" @click="settingsMenu = 'root'"><ArrowLeft aria-hidden="true" />推理强度</button>
        <button v-for="level in settings?.thinkingLevels ?? []" :key="level" type="button" class="pi-settings-menu__option" :disabled="settingsLoading" @click="chooseThinkingLevel(level)"><span>{{ thinkingLabels[level] ?? level }}</span><Check v-if="level === settings?.current.thinkingLevel" aria-label="当前推理强度" /></button>
      </template>
      <div v-if="settingsLoading" class="pi-settings-menu__loading"><Loader2 aria-hidden="true" />正在同步 Pi 设置…</div>
    </div>
    <textarea class="agent-turn-input" aria-label="补充本地 Pi 指令" rows="3" :value="modelValue" :placeholder="canSubmit ? '补充本轮指令…' : '当前执行完成后可提交下一轮…'" :disabled="submitting || canceling" @input="updatePrompt" @keydown.meta.enter.prevent="submitTurn" @keydown.ctrl.enter.prevent="submitTurn" />
    <button type="button" class="pi-settings-trigger" :disabled="!settingsEnabled || settingsLoading" :aria-expanded="settingsMenu !== 'closed'" aria-haspopup="menu" :title="modelLabel ? `${props.settings?.current.model.provider} / ${props.settings?.current.model.modelId}` : '模型与推理强度'" @click.stop="toggleSettings">
      <Loader2 v-if="settingsLoading" class="pi-turn-spinner" aria-hidden="true" />
      <template v-else-if="settings"><span class="pi-settings-trigger__model">{{ modelLabel }}</span><span>{{ thinkingLabel }}</span><ChevronDown aria-hidden="true" /></template>
      <template v-else>模型设置<ChevronDown aria-hidden="true" /></template>
    </button>
    <button type="button" class="agent-submit-button" :class="{ 'agent-submit-button--stop': active }" :disabled="submitting || canceling || (!active && (!modelValue.trim() || !canSubmit))" :aria-busy="submitting || canceling" :aria-label="active ? '停止本地 Pi' : '发送给本地 Pi'" :title="active ? '停止本地 Pi' : '发送给本地 Pi'" @click="triggerPrimaryAction"><Loader2 v-if="submitting || canceling" class="pi-turn-spinner" aria-hidden="true" /><CircleX v-else-if="active" aria-hidden="true" /><ArrowUp v-else aria-hidden="true" /></button>
  </div>
</template>

<style scoped>
.pi-turn-composer { position: relative; flex: 0 0 auto; margin-top: 12px; min-height: 96px; padding: 12px 14px 10px; border: 1px solid var(--color-border); border-radius: 20px; background: var(--color-bg-base); box-shadow: var(--shadow-subtle); transition: border-color var(--transition-fast); }.pi-turn-composer:focus-within { border-color: var(--color-border-strong); }
.agent-turn-input { display: block; width: 100%; height: 72px; min-height: 72px; resize: none; padding: 2px 52px 34px 1px; border: 0; color: var(--color-text-primary); background: transparent; font: inherit; font-size: var(--font-size-body); line-height: 1.55; }.agent-turn-input::placeholder { color: var(--color-text-tertiary); }.agent-turn-input:focus { outline: none; }.agent-turn-input:disabled { cursor: wait; opacity: .65; }
.pi-settings-trigger { display: inline-flex; align-items: center; gap: 6px; position: absolute; left: 12px; bottom: 10px; max-width: calc(100% - 72px); height: 30px; padding: 0 12px; border: 0; border-radius: var(--radius-full); color: var(--color-text-primary); background: var(--color-bg-hover); cursor: pointer; font: inherit; font-size: var(--font-size-caption); }.pi-settings-trigger:disabled { cursor: not-allowed; opacity: .55; }.pi-settings-trigger:focus-visible, .pi-settings-menu button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }.pi-settings-trigger svg { flex: 0 0 auto; width: 16px; height: 16px; }.pi-settings-trigger__model { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pi-settings-menu { position: absolute; z-index: 5; bottom: calc(100% + 8px); left: 0; width: min(330px, calc(100vw - 40px)); max-height: 350px; overflow-y: auto; padding: 8px; border: 1px solid var(--color-border); border-radius: 18px; background: var(--color-bg-base); box-shadow: var(--shadow-lg); }.pi-settings-menu__row, .pi-settings-menu__option, .pi-settings-menu__back { display: flex; align-items: center; width: 100%; min-height: 44px; gap: 10px; padding: 0 10px; border: 0; border-radius: var(--radius-sm); color: var(--color-text-primary); background: transparent; cursor: pointer; font: inherit; text-align: left; }.pi-settings-menu__row:hover:not(:disabled), .pi-settings-menu__option:hover:not(:disabled), .pi-settings-menu__back:hover { background: var(--color-bg-hover); }.pi-settings-menu__row:disabled, .pi-settings-menu__option:disabled { cursor: wait; opacity: .6; }.pi-settings-menu__row > :first-child { font-weight: 600; }.pi-settings-menu__row svg, .pi-settings-menu__option svg, .pi-settings-menu__back svg { flex: 0 0 auto; width: 18px; height: 18px; }.pi-settings-menu__value { overflow: hidden; flex: 1; color: var(--color-text-secondary); text-align: right; text-overflow: ellipsis; white-space: nowrap; }.pi-settings-menu__group { padding: 4px 0; }.pi-settings-menu__group + .pi-settings-menu__group { border-top: 1px solid var(--color-border); }.pi-settings-menu__group-label { display: block; padding: 4px 10px; color: var(--color-text-tertiary); font-size: var(--font-size-caption); }.pi-settings-menu__option > span { overflow: hidden; flex: 1; text-overflow: ellipsis; white-space: nowrap; }.pi-settings-menu__option svg { color: var(--color-accent); }.pi-settings-menu__loading { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px; color: var(--color-text-secondary); font-size: var(--font-size-caption); }.pi-settings-menu__loading svg { width: 16px; height: 16px; animation: pi-turn-spin 1s linear infinite; }
.agent-submit-button { display: inline-flex; align-items: center; justify-content: center; position: absolute; right: 10px; bottom: 10px; width: 40px; height: 40px; padding: 0; border: 0; border-radius: var(--radius-full); color: var(--color-text-primary); background: var(--color-text-tertiary); cursor: pointer; }.agent-submit-button:hover:not(:disabled) { color: var(--color-bg-base); background: var(--color-text-primary); }.agent-submit-button--stop { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 10%, var(--color-bg-base)); }.agent-submit-button--stop:hover:not(:disabled) { color: var(--color-bg-base); background: var(--color-danger); }.agent-submit-button:disabled { cursor: not-allowed; opacity: .5; }.agent-submit-button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; }.agent-submit-button svg { width: 18px; height: 18px; }.pi-turn-spinner { animation: pi-turn-spin 1s linear infinite; }@keyframes pi-turn-spin { to { transform: rotate(360deg); } }
</style>
