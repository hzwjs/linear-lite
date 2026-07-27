<script setup lang="ts">
defineProps<{ title: string; description: string; action?: string }>()
defineEmits<{ action: [] }>()
</script>

<template>
  <div class="mobile-empty-state">
    <div class="mobile-empty-mark" aria-hidden="true"><span /></div>
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
    <button v-if="action" type="button" class="mobile-secondary-button" @click="$emit('action')">
      {{ action }}
    </button>
  </div>
</template>
