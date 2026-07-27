<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps<{ modelValue: boolean; title: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const sheet = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
let previousFocus: HTMLElement | null = null
let background: HTMLElement | null = null
let backgroundWasInert = false
let previousBodyOverflow = ''

function close() {
  emit('update:modelValue', false)
}

function onKeydown(event: KeyboardEvent) {
  if (!props.modelValue) return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !sheet.value) return
  const focusable = [...sheet.value.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.hasAttribute('hidden'))
  if (!focusable.length) {
    event.preventDefault()
    sheet.value.focus()
    return
  }
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function restorePage() {
  if (background) background.inert = backgroundWasInert
  document.body.style.overflow = previousBodyOverflow
  previousFocus?.focus()
  background = null
  previousFocus = null
}

watch(() => props.modelValue, async (open) => {
  if (open) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    background = document.querySelector<HTMLElement>('.mobile-app')
    backgroundWasInert = background?.inert ?? false
    if (background) background.inert = true
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    closeButton.value?.focus()
  } else {
    restorePage()
  }
})

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  if (props.modelValue) restorePage()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="mobile-sheet">
      <div v-if="modelValue" class="mobile-sheet-layer" role="presentation" @click.self="close">
        <section ref="sheet" class="mobile-sheet" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1">
          <div class="mobile-sheet-grabber" aria-hidden="true" />
          <header class="mobile-sheet-header">
            <h2>{{ title }}</h2>
            <button ref="closeButton" type="button" class="mobile-icon-button" aria-label="关闭" @click="close">
              <X :size="20" />
            </button>
          </header>
          <div class="mobile-sheet-body"><slot /></div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
