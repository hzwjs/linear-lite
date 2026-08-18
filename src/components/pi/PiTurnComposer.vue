<script setup lang="ts">
import { ArrowUp, CircleX, Loader2 } from 'lucide-vue-next'

const props = defineProps<{
  modelValue: string
  active: boolean
  canSubmit: boolean
  submitting: boolean
  canceling: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
  stop: []
}>()

function updatePrompt(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}

function triggerPrimaryAction() {
  if (props.active) emit('stop')
  else emit('submit')
}
</script>

<template>
  <div class="pi-turn-composer">
    <textarea
      class="agent-turn-input"
      aria-label="补充本地 Pi 指令"
      rows="3"
      :value="modelValue"
      :placeholder="canSubmit ? '补充本轮指令…' : '当前执行完成后可提交下一轮…'"
      :disabled="submitting || canceling"
      @input="updatePrompt"
      @keydown.meta.enter.prevent="emit('submit')"
      @keydown.ctrl.enter.prevent="emit('submit')"
    />
    <div class="pi-turn-footer">
      <span class="pi-turn-hint">⌘↵ / Ctrl↵ 发送</span>
      <button
        type="button"
        class="agent-submit-button"
        :class="{ 'agent-submit-button--stop': active }"
        :disabled="submitting || canceling || (!active && (!modelValue.trim() || !canSubmit))"
        :aria-busy="submitting || canceling"
        :aria-label="active ? '停止本地 Pi' : '发送给本地 Pi'"
        :title="active ? '停止本地 Pi' : '发送给本地 Pi'"
        @click="triggerPrimaryAction"
      >
        <Loader2 v-if="submitting || canceling" class="pi-turn-spinner" aria-hidden="true" />
        <CircleX v-else-if="active" aria-hidden="true" />
        <ArrowUp v-else aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.pi-turn-composer {
  flex: 0 0 auto;
  margin-top: 12px;
  padding: 12px 14px 10px;
  border: 1px solid var(--color-border);
  border-radius: 20px;
  background: var(--color-bg-base);
  box-shadow: var(--shadow-subtle);
  transition: border-color var(--transition-fast);
}
.pi-turn-composer:focus-within { border-color: var(--color-border-strong); }
.agent-turn-input {
  display: block;
  width: 100%;
  height: 72px;
  min-height: 72px;
  resize: none;
  padding: 2px 1px 8px;
  border: 0;
  color: var(--color-text-primary);
  background: transparent;
  font: inherit;
  font-size: var(--font-size-body);
  line-height: 1.55;
}
.agent-turn-input::placeholder { color: var(--color-text-tertiary); }
.agent-turn-input:focus { outline: none; }
.agent-turn-input:disabled { cursor: wait; opacity: 0.65; }
.pi-turn-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 36px;
}
.pi-turn-hint {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
  white-space: nowrap;
}
.agent-submit-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
  color: var(--color-text-primary);
  background: var(--color-text-tertiary);
  cursor: pointer;
}
.agent-submit-button:hover:not(:disabled) { color: var(--color-bg-base); background: var(--color-text-primary); }
.agent-submit-button--stop { color: var(--color-danger); background: color-mix(in srgb, var(--color-danger) 10%, var(--color-bg-base)); }
.agent-submit-button--stop:hover:not(:disabled) { color: var(--color-bg-base); background: var(--color-danger); }
.agent-submit-button:disabled { cursor: not-allowed; opacity: 0.5; }
.agent-submit-button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; }
.agent-submit-button svg { width: 18px; height: 18px; }
.pi-turn-spinner { animation: pi-turn-spin 1s linear infinite; }
@keyframes pi-turn-spin { to { transform: rotate(360deg); } }
</style>
