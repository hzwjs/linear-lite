<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { AttemptContext, AttemptPrerequisiteReviews, CollaborationAttempt, DependencyFact } from '../services/api/collaborationBatch'
import type { PrerequisiteReviewStatus } from '../services/api/types'
import CollaborationAttemptDetails from './CollaborationAttemptDetails.vue'
import CollaborationStatusBadge from './ui/CollaborationStatusBadge.vue'

const props = defineProps<{
  attempts: CollaborationAttempt[]
  contextFacts: AttemptContext[]
  prerequisiteReviewFacts: AttemptPrerequisiteReviews[]
  prerequisiteReviewStatuses: PrerequisiteReviewStatus[]
  dependencyFacts: DependencyFact[]
  activeOrFrozen: boolean
  serverTime: string
}>()
const { t, te } = useI18n()

function contextFor(attemptId: number) {
  return props.contextFacts.find(item => item.attemptId === attemptId)
}
function reviewsFor(attemptId: number) {
  return props.prerequisiteReviewFacts.find(item => item.attemptId === attemptId)
}
function label(group: 'reviewDecision' | 'materialStatus' | 'dependencyStatus', value: string) {
  const key = `taskCollaboration.${group}.${value}`
  return te(key) ? t(key) : t('collaborationBatch.unknownState', { state: value })
}
function dependencyStatus(fact: DependencyFact) {
  if (!fact.accepted) return 'REJECTED'
  if (fact.outcome === 'MISSING_OUTSIDE_BATCH' || fact.outcome === 'GOAL_AUTHORIZATION_REQUIRED') return fact.outcome
  return fact.change.action
}
function tone(status: string) {
  if (['CURRENT', 'ADOPT', 'NOT_APPLICABLE'].includes(status)) return 'success'
  if (['PENDING_CONFIRMATION', 'REVIEW_REQUIRED', 'WAIT_FOR_CHANGE', 'REQUEST_REWORK', 'REWORK_REQUESTED',
    'GOAL_AUTHORIZATION_REQUIRED', 'REPAIR_IN_SCOPE'].includes(status)) return 'warning'
  return 'default'
}
</script>

<template>
  <div class="collaboration-facts">
    <CollaborationStatusBadge v-if="activeOrFrozen" tone="warning">{{ t('taskCollaboration.activeOrFrozen') }}</CollaborationStatusBadge>
    <section v-if="prerequisiteReviewStatuses.length" class="fact-group">
      <strong>{{ t('taskCollaboration.reviewTitle') }}</strong>
      <ul class="fact-list"><li v-for="status in prerequisiteReviewStatuses" :key="status.sourceAttemptId">
        <CollaborationStatusBadge :tone="tone(status.materialStatus)">{{ label('materialStatus', status.materialStatus) }}</CollaborationStatusBadge>
        <span>{{ status.reason }}</span>
      </li></ul>
    </section>
    <section v-if="dependencyFacts.length" class="fact-group">
      <strong>{{ t('taskCollaboration.dependencyTitle') }}</strong>
      <ul class="fact-list"><li v-for="fact in dependencyFacts" :key="fact.operationId">
        <CollaborationStatusBadge :tone="fact.accepted && fact.change.action === 'REMOVE' ? 'default' : 'warning'">
          {{ label('dependencyStatus', dependencyStatus(fact)) }}
        </CollaborationStatusBadge>
        <span>{{ fact.change.missing }}</span>
        <span>{{ fact.change.reason }}</span>
        <span>{{ fact.change.evidence }}</span>
      </li></ul>
    </section>
    <slot name="goal-authorization" />
    <section v-for="attempt in attempts" :key="attempt.id" class="fact-group">
      <CollaborationAttemptDetails :attempt="attempt" :server-time="serverTime" />
      <div v-if="contextFor(attempt.id)?.refs.length" class="fact-group">
        <strong>{{ t('taskCollaboration.contextTitle') }}</strong>
        <ul class="fact-list"><li v-for="(item, index) in contextFor(attempt.id)?.refs" :key="index">
          <span>{{ item.ref.kind === 'DOCUMENT' ? (item.documentTitle ?? t('taskCollaboration.documentUnavailable')) : item.ref.projectPath }}</span>
          <CollaborationStatusBadge :tone="tone(item.status)">{{ label('materialStatus', item.status) }}</CollaborationStatusBadge>
        </li></ul>
      </div>
      <div v-if="reviewsFor(attempt.id)?.reviews.length" class="fact-group">
        <strong>{{ t('taskCollaboration.reviewTitle') }}</strong>
        <ul class="fact-list"><li v-for="fact in reviewsFor(attempt.id)?.reviews" :key="fact.operationId">
          <CollaborationStatusBadge :tone="tone(fact.outcome)">{{ label('reviewDecision', fact.outcome) }}</CollaborationStatusBadge>
          <span>{{ fact.review.finding }}</span><span>{{ fact.review.evidence }}</span>
          <ul v-if="fact.review.observations.length" class="fact-list">
            <li v-for="observation in fact.review.observations" :key="observation.path">
              <span>{{ observation.path }}</span>
              <CollaborationStatusBadge :tone="observation.state === 'MISSING' ? 'warning' : 'success'">
                {{ t(`taskCollaboration.artifactState.${observation.state}`) }}
              </CollaborationStatusBadge>
            </li>
          </ul>
        </li></ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.collaboration-facts, .fact-group, .fact-list { display: grid; gap: 8px; }
.fact-group { min-width: 0; }
.fact-list { padding: 0; margin: 0; list-style: none; }
.fact-list li { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; overflow-wrap: anywhere; }
</style>
