<script setup lang="ts">
import type { AutomationPollHealth } from '@/types/domain'
import { formatCompactDateTime } from '@/utils/dateTime'
import { userFacingError } from '@/utils/displayLabels'
defineProps<{ health?: AutomationPollHealth; enabled: boolean; scheduled: boolean }>()
</script>

<template>
  <div v-if="health" class="poll-health" :class="{ failed: health.status === 'FAILED' }" aria-label="自动化检测状态">
    <template v-if="health.status === 'FAILED'">
      <strong>检测失败 · 连续 {{ health.consecutiveFailures }} 次</strong>
      <p>{{ userFacingError(health.errorMessage, '检测暂时不可用，请稍后刷新。') }}</p>
    </template>
    <strong v-else>检测正常</strong>
    <small>最近检查 {{ formatCompactDateTime(health.lastCheckedAt) }}<template v-if="health.lastSuccessAt && health.status === 'FAILED'"> · 最近成功 {{ formatCompactDateTime(health.lastSuccessAt) }}</template></small>
  </div>
  <small v-else-if="enabled && scheduled">等待首次检测</small>
</template>

<style scoped>
.poll-health{display:grid;gap:4px;font-size:11px;color:var(--color-text-secondary)}
.poll-health.failed{border-left:2px solid var(--color-session-warning);padding-left:8px}
.poll-health.failed strong{color:var(--color-session-warning)}
p{margin:0;overflow-wrap:anywhere}small{color:var(--color-text-muted)}
</style>
