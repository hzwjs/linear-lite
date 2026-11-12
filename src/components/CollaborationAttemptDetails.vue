<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CollaborationAttempt } from '../services/api/collaborationBatch'
import { collaborationAttemptTone } from '../utils/collaborationBatchDisplay'
import CollaborationStatusBadge from './ui/CollaborationStatusBadge.vue'

const props = defineProps<{ attempt: CollaborationAttempt; serverTime: string }>()
const { t, te } = useI18n()
const checkpoints = computed(() => props.attempt.checkpoints.filter(checkpoint =>
  checkpoint.finding || checkpoint.blockedReason || Array.isArray(checkpoint.artifactRefs) && checkpoint.artifactRefs.length))
const displayState = computed(() => {
  const { leaseUntil, status, stopRequestedAt, stopConfirmedAt } = props.attempt
  if (stopConfirmedAt !== null) return status
  if (leaseUntil === null || Date.parse(leaseUntil) <= Date.parse(props.serverTime)) return 'recovery'
  if (status !== 'running' && status !== 'stop_requested') return 'recovery'
  if (stopRequestedAt !== null) return 'stop_requested'
  return status
})
function label(state: string) {
  const key = `collaborationBatch.attemptState.${state}`
  return te(key) ? t(key) : t('collaborationBatch.unknownState', { state })
}
</script>

<template>
  <section class="attempt">
    <div class="attempt-heading"><CollaborationStatusBadge :tone="collaborationAttemptTone(displayState)">{{ label(displayState) }}</CollaborationStatusBadge></div>
    <dl class="attempt-facts">
      <div v-if="attempt.stopReason"><dt>{{ t('collaborationBatch.stopReason') }}</dt><dd>{{ attempt.stopReason }}</dd></div>
      <div v-if="attempt.stopConfirmedAt"><dt>{{ t('collaborationBatch.stopConfirmed') }}</dt><dd>{{ attempt.stopConfirmedAt }}</dd></div>
    </dl>
    <div v-if="checkpoints.length" class="attempt-evidence"><h4>{{ t('collaborationBatch.checkpoints') }}</h4>
      <ol><li v-for="checkpoint in checkpoints" :key="checkpoint.operationId">
        <span>{{ t('collaborationBatch.occurredAt') }}: {{ checkpoint.occurredAt }}</span>
        <p v-if="checkpoint.finding">{{ t('collaborationBatch.finding') }}: {{ checkpoint.finding }}</p>
        <p v-if="checkpoint.blockedReason">{{ t('collaborationBatch.blockedReason') }}: {{ checkpoint.blockedReason }}</p>
        <div v-if="Array.isArray(checkpoint.artifactRefs) && checkpoint.artifactRefs.length">{{ t('collaborationBatch.artifactRefs') }}<ul><li v-for="ref in checkpoint.artifactRefs" :key="ref">{{ ref }}</li></ul></div>
      </li></ol>
    </div>
    <div v-if="attempt.resultEvidence != null" class="attempt-evidence"><h4>{{ t('collaborationBatch.results') }}</h4>
      <ol><li v-for="(condition, index) in attempt.resultEvidence.conditions" :key="index">
        <strong>{{ condition.condition }}</strong> — {{ condition.result }}
        <div v-if="condition.evidenceRefs.length">{{ t('collaborationBatch.evidenceRefs') }}<ul><li v-for="ref in condition.evidenceRefs" :key="ref">{{ ref }}</li></ul></div>
      </li></ol>
      <div v-if="attempt.resultEvidence.changedPaths.length">{{ t('collaborationBatch.changedPaths') }}<ul><li v-for="ref in attempt.resultEvidence.changedPaths" :key="ref">{{ ref }}</li></ul></div>
      <div v-if="attempt.resultEvidence.artifactRefs.length">{{ t('collaborationBatch.artifactRefs') }}<ul><li v-for="ref in attempt.resultEvidence.artifactRefs" :key="ref">{{ ref }}</li></ul></div>
    </div>
  </section>
</template>

<style scoped>
.attempt { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.attempt-facts { display: flex; flex-wrap: wrap; gap: 12px 20px; margin: 0; }
.attempt-facts div { min-width: 0; }
.attempt-facts dt, .attempt-evidence h4 { color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.attempt-facts dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.attempt-evidence h4 { margin: 0 0 4px; font-weight: var(--font-weight-medium); }
.attempt-evidence ol, .attempt-evidence ul { display: grid; gap: 4px; margin: 4px 0; padding-left: 20px; overflow-wrap: anywhere; }
.attempt-evidence p { margin: 4px 0; }
</style>
