<script setup lang="ts">
import { computed } from 'vue'
import type { TaskStatus } from '@/types/domain'

const props = defineProps<{ status: TaskStatus | 'ONLINE' | 'OFFLINE' | 'STARTING' | 'INCOMPATIBLE' | 'PASS' | 'FAIL' | 'PENDING'; label?: string }>()

const tone = computed(() => {
  if (['RUNNING', 'VERIFYING', 'JUDGING', 'ONLINE'].includes(props.status)) return 'status-running'
  if (['SUCCEEDED', 'PASS'].includes(props.status)) return 'status-success'
  if (['RETRY_WAIT', 'STARTING'].includes(props.status)) return 'status-session'
  if (['FAILED', 'CANCELLED', 'OFFLINE', 'INCOMPATIBLE', 'FAIL'].includes(props.status)) return 'status-danger'
  if (props.status === 'WAITING_INPUT') return 'status-ai'
  return 'status-pending'
})

const display = computed(() => props.label ?? props.status.replace(/_/g, ' '))
</script>

<template><span :class="['status-badge', tone]">{{ display }}</span></template>
