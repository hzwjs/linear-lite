<script setup lang="ts">
defineProps<{ content: string }>()
</script>

<template>
  <article class="pi-user-message">
    <span class="pi-user-message__author">你</span>
    <div class="pi-user-message__content">{{ content }}</div>
  </article>
</template>

<style scoped>
.pi-user-message {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
  max-width: 88%;
  margin: 0 0 20px auto;
}
.pi-user-message__author {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
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
