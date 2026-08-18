<script setup lang="ts">
defineProps<{ content: string }>()
</script>

<template>
  <article class="pi-user-message">
    <div class="pi-user-message__content">{{ content }}</div>
  </article>
</template>

<style scoped>
.pi-user-message {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  max-width: 88%;
  margin: 0 0 20px auto;
}
.pi-user-message__content {
  padding: 9px 12px;
  border: 1px solid var(--color-accent-muted-border);
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
  font-size: var(--font-size-body);
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
