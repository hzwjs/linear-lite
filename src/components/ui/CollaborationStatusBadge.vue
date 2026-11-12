<script setup lang="ts">
import type { BatchTone } from '../../utils/collaborationBatchDisplay'
defineProps<{ tone: BatchTone }>()
</script>

<template><span class="status-badge" :data-tone="tone"><slot /></span></template>

<style scoped>
.status-badge { display: inline-flex; align-items: center; min-height: 21px; padding: 2px 6px; border-radius: var(--radius-sm); background: var(--color-bg-muted); color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.status-badge[data-tone="processing"] { color: var(--color-status-in-progress); }
.status-badge[data-tone="success"] { color: var(--color-success); }
.status-badge[data-tone="warning"] { color: var(--color-attention); }
.status-badge[data-tone="error"] { color: var(--color-danger); }
</style>
