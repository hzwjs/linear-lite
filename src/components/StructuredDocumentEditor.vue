<script setup lang="ts">
import { ref } from 'vue'
import BlockNoteEditorWrapper from './BlockNoteEditorWrapper.vue'

withDefaults(
  defineProps<{
    modelValue: string
    readonly?: boolean
    placeholder?: string
    mentionMembers?: Array<{ id: number; label: string }>
    mentionDocuments?: Array<{ id: number; title: string; projectId: number }>
  }>(),
  {
    readonly: false,
    placeholder: '',
    mentionMembers: () => [],
    mentionDocuments: () => []
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  blur: []
  focus: []
}>()

const editorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)

function focus() {
  editorRef.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <BlockNoteEditorWrapper
    ref="editorRef"
    class="structured-document-editor"
    :model-value="modelValue"
    :readonly="readonly"
    :placeholder="placeholder"
    :mention-members="mentionMembers"
    :mention-documents="mentionDocuments"
    :mention-members-group-text="$t('documents.mentionMembersGroup')"
    :mention-documents-group-text="$t('documents.mentionDocumentsGroup')"
    :mention-menu-no-matches-text="$t('documents.mentionNoMatches')"
    :mention-menu-loading-text="$t('common.loading')"
    :min-height="320"
    block-chrome
    @update:model-value="emit('update:modelValue', $event)"
    @blur="emit('blur')"
    @focus="emit('focus')"
  />
</template>

<style scoped>
.structured-document-editor {
  min-height: 320px;
  background: transparent;
}

.structured-document-editor :deep(.bn-editor) {
  padding-inline: 0 !important;
  font-size: 15px;
  line-height: 1.65;
}
</style>
