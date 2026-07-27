<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import DesktopApp from './App.vue'

const MobileApp = defineAsyncComponent(() => import('./mobile/MobileApp.vue'))
const mobileQuery = '(max-width: 1099px)'
const media = typeof window === 'undefined' ? null : window.matchMedia(mobileQuery)
const isMobile = ref(media?.matches ?? false)

function syncViewport(event?: MediaQueryListEvent) {
  isMobile.value = event?.matches ?? media?.matches ?? false
}

onMounted(() => {
  syncViewport()
  media?.addEventListener('change', syncViewport)
})

onBeforeUnmount(() => media?.removeEventListener('change', syncViewport))

const activeApp = computed(() => (isMobile.value ? MobileApp : DesktopApp))
</script>

<template>
  <component :is="activeApp" />
</template>
