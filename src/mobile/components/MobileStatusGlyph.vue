<script setup lang="ts">
import { computed } from 'vue'
import { CheckCircle2, Circle, CircleDashed, CircleX, Copy, Eye, LoaderCircle } from 'lucide-vue-next'
import type { Status } from '../../types/domain'

const props = withDefaults(defineProps<{ status: Status; size?: number }>(), { size: 18 })
const icon = computed(() => ({
  backlog: CircleDashed,
  todo: Circle,
  in_progress: LoaderCircle,
  in_review: Eye,
  done: CheckCircle2,
  canceled: CircleX,
  duplicate: Copy
})[props.status])
</script>

<template>
  <component :is="icon" :size="size" class="mobile-status-glyph" :class="`is-${status}`" aria-hidden="true" />
</template>
