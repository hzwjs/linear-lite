<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { basicSetup, EditorView } from 'codemirror'
import { html } from '@codemirror/lang-html'
import { Compartment } from '@codemirror/state'
import { placeholder } from '@codemirror/view'
import { yCollab } from 'y-codemirror.next'
import type { Awareness } from 'y-protocols/awareness'
import type * as Y from 'yjs'

const props = defineProps<{
  text: Y.Text
  awareness: Awareness
  disabled?: boolean
  ariaLabel: string
  placeholder: string
}>()
const emit = defineEmits<{ change: [content: string]; ready: [] }>()
const host = ref<HTMLElement | null>(null)
let view: EditorView | null = null
const editable = new Compartment()

onMounted(() => {
  if (host.value == null) return
  view = new EditorView({
    parent: host.value,
    doc: props.text.toString(),
    extensions: [
      basicSetup,
      html(),
      yCollab(props.text, props.awareness),
      editable.of(EditorView.editable.of(!props.disabled)),
      placeholder(props.placeholder),
      EditorView.contentAttributes.of({
        'aria-label': props.ariaLabel,
        'aria-describedby': 'html-source-hint',
        spellcheck: 'false',
        autocapitalize: 'off',
        autocomplete: 'off',
      }),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) emit('change', update.state.doc.toString())
      }),
    ],
  })
  emit('change', view.state.doc.toString())
  emit('ready')
})

watch(() => props.disabled, (disabled) => {
  if (view == null) return
  view.dispatch({ effects: editable.reconfigure(EditorView.editable.of(!disabled)) })
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})
</script>

<template>
  <div ref="host" class="collaborative-html-source" :aria-label="ariaLabel" />
</template>

<style>
.collaborative-html-source { min-height: 0; flex: 1; overflow: auto; }
.collaborative-html-source .cm-editor { min-height: 100%; color: var(--color-text-primary); background: transparent; }
.collaborative-html-source .cm-scroller { font-family: var(--font-family-mono, ui-monospace, monospace); font-size: 13px; }
.collaborative-html-source .cm-content { min-height: 100%; padding: 10px 12px 36px; }
.collaborative-html-source .cm-gutters { background: var(--color-bg-subtle); color: var(--color-text-muted); border-color: var(--color-border-subtle); }
.collaborative-html-source .cm-focused { outline: 2px solid var(--color-accent); outline-offset: -2px; }
</style>
