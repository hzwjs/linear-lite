<script setup lang="ts">
import { NodeViewWrapper, NodeViewContent } from '@tiptap/vue-3'
import type { NodeViewProps } from '@tiptap/core'
import { Copy } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<NodeViewProps>()
const { t } = useI18n()
const codeLanguages = computed(() => [
  { value: '', label: t('editor.codeBlock.languages.plainText') },
  { value: 'javascript', label: t('editor.codeBlock.languages.javascript') },
  { value: 'typescript', label: t('editor.codeBlock.languages.typescript') },
  { value: 'sql', label: t('editor.codeBlock.languages.sql') },
  { value: 'json', label: t('editor.codeBlock.languages.json') },
  { value: 'html', label: t('editor.codeBlock.languages.html') },
  { value: 'css', label: t('editor.codeBlock.languages.css') },
  { value: 'bash', label: t('editor.codeBlock.languages.bash') },
  { value: 'python', label: t('editor.codeBlock.languages.python') },
  { value: 'java', label: t('editor.codeBlock.languages.java') },
  { value: 'xml', label: t('editor.codeBlock.languages.xml') },
])

function onLanguageChange(e: Event) {
  const value = (e.target as HTMLSelectElement).value
  props.updateAttributes({ language: value || null })
}

function copyCode() {
  const text = props.node.textContent ?? ''
  if (text) navigator.clipboard.writeText(text)
}
</script>

<template>
  <NodeViewWrapper as="div" class="code-block-linear">
    <div class="code-block-linear__box">
      <div class="code-block-linear__body">
        <NodeViewContent as="pre" class="code-block-linear__pre" />
      </div>
      <div class="code-block-linear__toolbar">
        <select
          class="code-block-linear__lang"
          :value="props.node.attrs.language ?? ''"
          :aria-label="t('editor.codeBlock.languageAria')"
          @change="onLanguageChange"
        >
          <option
            v-for="opt in codeLanguages"
            :key="opt.value || 'plain'"
            :value="opt.value"
          >
            {{ opt.label }}
          </option>
        </select>
        <button
          type="button"
          class="code-block-linear__copy"
          :aria-label="t('editor.codeBlock.copyAria')"
          @click="copyCode"
        >
          <Copy class="code-block-linear__copy-icon" />
        </button>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.code-block-linear {
  margin: 0.5em 0;
}
.code-block-linear__box {
  display: flex;
  align-items: flex-start;
  gap: 0;
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
  overflow: hidden;
  min-height: 40px;
}
.code-block-linear__body {
  flex: 1;
  min-width: 0;
  overflow-x: auto;
}
.code-block-linear__pre {
  margin: 0;
  padding: 10px 12px;
  font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace;
  font-size: var(--font-size-caption);
  line-height: 1.5;
  color: var(--color-text-primary);
  background: transparent;
  border: none;
  outline: none;
  white-space: pre;
}
.code-block-linear__toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px 6px 0;
  background: transparent;
}
.code-block-linear__lang {
  font-size: var(--font-size-caption);
  padding: 4px 20px 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--color-border-subtle);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 6px center;
}
.code-block-linear__copy {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}
.code-block-linear__copy:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.code-block-linear__copy-icon {
  width: 14px;
  height: 14px;
}
/* 语法高亮（lowlight 输出的 hljs 类名） */
.code-block-linear__pre :deep(.hljs-keyword),
.code-block-linear__pre :deep(.hljs-selector-tag) { color: #7c3aed; }
.code-block-linear__pre :deep(.hljs-string),
.code-block-linear__pre :deep(.hljs-attr) { color: #2d7d46; }
.code-block-linear__pre :deep(.hljs-number),
.code-block-linear__pre :deep(.hljs-literal) { color: #b65c00; }
.code-block-linear__pre :deep(.hljs-comment) { color: var(--color-text-muted); }
.code-block-linear__pre :deep(.hljs-built_in),
.code-block-linear__pre :deep(.hljs-title) { color: #475569; }
</style>
