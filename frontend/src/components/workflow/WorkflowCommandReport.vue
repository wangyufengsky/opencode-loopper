<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'COMMAND' || typeof value.valid !== 'boolean' || typeof value.passed !== 'boolean'
    || typeof value.timedOut !== 'boolean' || typeof value.cancelled !== 'boolean' || typeof value.outputTruncated !== 'boolean'
    || typeof value.output !== 'string' || typeof value.errorCode !== 'string' || typeof value.reportExcerpt !== 'boolean'
    || value.exitCode !== null && !Number.isInteger(value.exitCode)) return null
  return { valid: value.valid, passed: value.valid && value.passed, timedOut: value.timedOut, cancelled: value.cancelled,
    outputTruncated: value.outputTruncated, output: value.output, errorCode: value.errorCode, reportExcerpt: value.reportExcerpt, exitCode: value.exitCode }
})
</script>
<template><div class="workflow-command-report">
  <p v-if="!report" role="alert">命令报告格式无法读取，请重新读取本次交付物。</p>
  <template v-else><StatusBadge :status="report.passed ? 'PASS' : 'FAILED'" :label="!report.valid ? '检查未完成' : report.passed ? '检查通过' : '检查未通过'" />
    <p v-if="report.exitCode !== null">进程退出码：{{ report.exitCode }}</p>
    <p v-if="report.timedOut">命令超过执行时限。</p><p v-if="report.cancelled">本次检查已取消。</p><p v-if="report.outputTruncated">输出不完整，不能作为完整检查结果。</p>
    <p v-if="report.errorCode" role="alert">{{ workflowReasonLabel(report.errorCode) }}</p>
    <p v-if="report.reportExcerpt">报告仅展示输出摘要，完整的已保存输出可在执行记录中查看。</p>
    <pre v-if="report.output" class="workflow-command-output" aria-label="命令输出摘要">{{ report.output }}</pre>
  </template>
</div></template>
