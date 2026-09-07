<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ code?: string; detail?: string }>()
const labels: Record<string, string> = {
  PACKAGE_GAP_CANDIDATE_EXPRESSION: '候选表达待修正',
  PACKAGE_GAP_REPOSITORY_UNCONFIRMED: '仓库事实待确认',
  PACKAGE_GAP_VERIFICATION_TO_BUILD: '需要建设验证',
  PACKAGE_GAP_BUSINESS_DECISION: '业务选择待确认',
  PACKAGE_GAP_PROVEN_CONFLICT: '已证实约束冲突',
  PACKAGE_GAP_UNCONFIRMED: '尚未确认',
  PACKAGE_SOURCE_UNCONFIRMED: '来源语义尚未确认',
  PACKAGE_BEHAVIOR_TIMEOUT: '语义检查超时',
  PACKAGE_BEHAVIOR_STATE_CONFLICT: '语义准备需要恢复',
}
const label = computed(() => props.code ? labels[props.code] : undefined)
</script>

<template>
  <aside v-if="label" class="package-gap-notice" role="status" aria-label="工作包待处理事项">
    <strong>{{ label }}</strong>
    <p v-if="detail">{{ detail }}</p>
    <p v-if="code === 'PACKAGE_SOURCE_UNCONFIRMED' || code === 'PACKAGE_GAP_UNCONFIRMED' || code === 'PACKAGE_GAP_REPOSITORY_UNCONFIRMED'">现有依据不足以确认缺少需求或能力。请保留已知行为，补充证据或通过本地反馈说明。</p>
    <p v-else-if="code === 'PACKAGE_GAP_BUSINESS_DECISION'">请在本地反馈中明确不同选择对应的行为，再重新设计。可按诊断中的来源回复，例如 REQ-L001=失败时回滚；系统保留原文和你的补充。</p>
  </aside>
</template>

<style scoped>
.package-gap-notice { margin: 12px 20px; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 8px; }
.package-gap-notice p { margin: 6px 0 0; font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
