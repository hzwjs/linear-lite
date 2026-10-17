<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Awareness } from 'y-protocols/awareness'
import { useDocumentCollaboration } from '../../composables/useDocumentCollaboration'
import type { DocumentCollaborationSession } from '../../services/api/documentCollaboration'
import CollaborativeHtmlSource from './CollaborativeHtmlSource.vue'

const props = defineProps<{ documentId: number; visible: boolean; ariaLabel: string; placeholder: string; initialSession?: Promise<DocumentCollaborationSession> }>()
const emit = defineEmits<{
  content: [content: string]
  status: [status: { saveState: 'saved' | 'saving' | 'offline'; connectionState: string; hasSynced: boolean; collaborators: Array<{ id: number; name: string; color: string }>; version: number; commitSeq: number }]
}>()

const collaboration = useDocumentCollaboration(props.documentId, 'HTML')
const currentText = computed(() => collaboration.ydoc.value.getText('html'))
const previewText = computed(() => collaboration.previewDoc.value?.getText('html') ?? null)
const previewAwareness = shallowRef<Awareness | null>(null)
const sourceReady = computed(() => collaboration.hasSynced.value && collaboration.provider.value != null)
const liveReady = ref(false)
let observedText = currentText.value

watch(collaboration.previewDoc, (doc) => {
  previewAwareness.value?.destroy()
  previewAwareness.value = doc == null ? null : new Awareness(doc)
  if (doc != null) emit('content', doc.getText('html').toString())
})

async function onLiveReady() {
  if (liveReady.value || !sourceReady.value) return
  const liveDoc = collaboration.ydoc.value
  const epoch = collaboration.session.value?.epoch
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  if (!sourceReady.value || collaboration.ydoc.value !== liveDoc || collaboration.session.value?.epoch !== epoch) return
  liveReady.value = true
  await nextTick()
  collaboration.retirePreview()
}

function observeText() {
  observedText.unobserve(onTextChange)
  observedText = currentText.value
  observedText.observe(onTextChange)
  emit('content', observedText.toString())
}

function onTextChange() {
  emit('content', observedText.toString())
}

watch(() => collaboration.ydoc.value, observeText)
watch(collaboration.epochChanged, (changed) => {
  if (changed) {
    liveReady.value = false
    void collaboration.restartForEpoch()
  }
})
watch(
  [collaboration.saveState, collaboration.connectionState, collaboration.hasSynced, collaboration.collaborators, collaboration.serverVersion, collaboration.commitSeq],
  ([saveState, connectionState, hasSynced, collaborators, version, seq]) => emit('status', { saveState, connectionState, hasSynced, collaborators: [...collaborators], version, commitSeq: seq }),
  { immediate: true }
)
onMounted(() => {
  observedText.observe(onTextChange)
  void collaboration.start(props.initialSession)
})
onBeforeUnmount(() => {
  observedText.unobserve(onTextChange)
  previewAwareness.value?.destroy()
})
</script>

<template>
  <div class="html-collaboration">
    <CollaborativeHtmlSource
      v-if="previewText && previewAwareness && !liveReady"
      :key="`preview-${documentId}-${collaboration.previewDoc.value?.clientID ?? ''}`"
      :text="previewText"
      :awareness="previewAwareness"
      :ariaLabel="ariaLabel"
      :placeholder="placeholder"
      disabled
    />
    <CollaborativeHtmlSource
      v-if="sourceReady && collaboration.provider.value"
      :key="`live-${documentId}-${collaboration.ydoc.value.clientID}-${collaboration.session.value?.sessionId ?? ''}`"
      :class="{ 'html-collaboration-live-pending': !liveReady }"
      :text="currentText"
      :awareness="collaboration.provider.value.awareness!"
      :ariaLabel="ariaLabel"
      :placeholder="placeholder"
      :disabled="collaboration.authFailed.value"
      @change="emit('content', $event)"
      @ready="onLiveReady"
    />
    <div v-if="!previewText && !sourceReady" class="html-collaboration-loading" role="status">
      {{ visible ? $t(`documents.collaboration.${collaboration.connectionState.value === 'connecting' ? 'connecting' : 'connectionLost'}`) : '' }}
    </div>
  </div>
</template>

<style scoped>
.html-collaboration { position: relative; display: flex; flex: 1; min-height: 160px; }
.html-collaboration-live-pending { position: absolute; inset: 0; visibility: hidden; pointer-events: none; }
.html-collaboration-loading { min-height: 160px; flex: 1; color: var(--color-text-secondary); }
</style>
