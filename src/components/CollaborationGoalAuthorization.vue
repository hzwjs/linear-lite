<script setup lang="ts">
import { onUnmounted, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { collaborationBatchApi, type GoalAuthorizationRequest } from '../services/api/collaborationBatch'

const props = defineProps<{ projectId: number; requests: GoalAuthorizationRequest[] }>()
const emit = defineEmits<{ authorized: [] }>()
const { t } = useI18n()
const pending = reactive(new Set<string>())
const errors = reactive(new Map<string, string>())
let mounted = true
let projectSequence = 0
watch(() => props.projectId, () => {
  projectSequence++
  pending.clear()
  errors.clear()
}, { flush: 'sync' })
onUnmounted(() => { mounted = false })

function requestKey(request: GoalAuthorizationRequest) {
  return `${request.batchId}:${request.requestOperationId}`
}

async function authorize(request: GoalAuthorizationRequest) {
  const key = requestKey(request)
  if (pending.has(key) || request.authorizedBatchId
      || !window.confirm(t('collaborationBatch.goalConfirm', { goal: request.goal, impact: request.impact }))) return
  const projectId = props.projectId
  const sequence = projectSequence
  pending.add(key)
  errors.delete(key)
  try {
    const result = await collaborationBatchApi.authorizeGoal(projectId, request.batchId, request.requestOperationId)
    if (typeof result?.batchId !== 'string' || !result.batchId) throw new Error(t('collaborationBatch.goalFailed'))
    if (!mounted || projectSequence !== sequence) return
    emit('authorized')
  } catch (error) {
    if (!mounted || projectSequence !== sequence) return
    errors.set(key, error instanceof Error ? error.message : t('collaborationBatch.goalFailed'))
  } finally {
    if (mounted && projectSequence === sequence) pending.delete(key)
  }
}
</script>

<template>
  <section v-for="request in requests" :key="requestKey(request)" class="goal-authorization">
    <strong>{{ t('collaborationBatch.goalTitle') }}</strong>
    <p>{{ request.goal }}</p>
    <p>{{ t('collaborationBatch.goalImpact') }} {{ request.impact }}</p>
    <router-link v-if="request.authorizedBatchId" :to="{ name: 'project-collaboration', params: { projectId }, query: { batch: request.authorizedBatchId } }">
      {{ t('collaborationBatch.goalViewBatch') }}
    </router-link>
    <button v-else type="button" class="goal-authorize" :disabled="pending.has(requestKey(request))"
      @click="authorize(request)">
      {{ pending.has(requestKey(request)) ? t('collaborationBatch.goalAuthorizing') : t('collaborationBatch.goalAuthorize') }}
    </button>
    <p v-if="errors.get(requestKey(request))" class="goal-error" role="alert">{{ errors.get(requestKey(request)) }}</p>
  </section>
</template>

<style scoped>
.goal-authorization { display: grid; justify-items: start; gap: 8px; }
.goal-authorization p { margin: 0; overflow-wrap: anywhere; }
.goal-authorize { min-height: 36px; padding: 7px 12px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-md); background: var(--color-bg-primary); color: inherit; cursor: pointer; }
.goal-authorize:disabled { opacity: .55; cursor: not-allowed; }
.goal-error { color: var(--color-danger); }
</style>
