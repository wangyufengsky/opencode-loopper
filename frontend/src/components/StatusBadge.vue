<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel } from '@/utils/displayLabels'

const props = defineProps<{ status: string; label?: string }>()

const tone = computed(() => {
  if (['RUNNING', 'VERIFYING', 'JUDGING', 'ONLINE'].includes(props.status)) return 'status-running'
  if (['SUCCEEDED', 'PASS'].includes(props.status)) return 'status-success'
  if (['RETRY_WAIT', 'STARTING', 'STOPPING'].includes(props.status)) return 'status-session'
  if (['FAILED', 'CANCELLED', 'OFFLINE', 'INCOMPATIBLE', 'FAIL'].includes(props.status)) return 'status-danger'
  if (props.status === 'WAITING_INPUT') return 'status-ai'
  return 'status-pending'
})

const display = computed(() => props.label ?? statusLabel(props.status))
</script>

<template><span :class="['status-badge', tone]">{{ display }}</span></template>
