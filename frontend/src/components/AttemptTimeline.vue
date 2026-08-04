<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import type { Attempt } from '@/types/domain'

const props = defineProps<{ attempts: Attempt[] }>()
const ordered = computed(() => [...props.attempts].sort((left, right) => right.ordinal - left.ordinal))
function color(attempt: Attempt) {
  if (attempt.status === 'VERIFIED') return 'var(--color-success)'
  if (attempt.status === 'SESSION_ERROR') return 'var(--color-session-warning)'
  if (attempt.status === 'TASK_ERROR' || attempt.status === 'CANCELLED') return 'var(--color-task-danger)'
  if (attempt.status === 'VERIFIER_FAILED') return 'var(--color-accent-cyan)'
  return 'var(--color-accent-cyan)'
}
</script>

<template>
  <div v-if="ordered.length" class="timeline">
    <article v-for="attempt in ordered" :key="attempt.id" class="timeline-item">
      <span class="timeline-dot" :style="{ '--timeline-color': color(attempt) }" />
      <div class="timeline-main"><span class="timeline-title">Attempt {{ attempt.ordinal }}</span><StatusBadge :status="attempt.status === 'VERIFIED' ? 'PASS' : attempt.status === 'SESSION_ERROR' ? 'RETRY_WAIT' : attempt.status === 'VERIFIER_FAILED' ? 'PENDING' : attempt.status === 'TASK_ERROR' || attempt.status === 'CANCELLED' ? 'FAILED' : 'RUNNING'" :label="attempt.status.replace(/_/g, ' ')" /></div>
      <p class="timeline-copy">{{ attempt.summary }}</p>
      <p class="mono tiny muted">{{ attempt.sessionId ?? 'Session 未创建' }} · {{ attempt.startedAt }}</p>
    </article>
  </div>
  <div v-else class="empty-state"><div><strong>尚未开始 Attempt</strong><p>此 Stage 会在前置阶段完成后自动加入调度队列。</p></div></div>
</template>
