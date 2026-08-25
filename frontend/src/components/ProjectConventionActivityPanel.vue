<script setup lang="ts">
import AiActivityPanel from '@/components/AiActivityPanel.vue'
import type { ProjectConventionActivity } from '@/types/domain'
import { statusLabel } from '@/utils/displayLabels'

defineProps<{
  activity?: ProjectConventionActivity
  loading?: boolean
  error?: string
}>()

</script>

<template>
  <AiActivityPanel title="项目公约设计师正在处理" panel-label="项目公约设计师正在处理"
    :state-text="activity?.remoteState ? statusLabel(activity.remoteState) : '连接只读 AI 会话'"
    :activity="activity" :loading="loading" :error="error" />
</template>
