<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowSnapshotPartialReport } from '@/types/domain'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ requirement: string; node: string; attempt: string }>()
const report = ref<WorkflowSnapshotPartialReport>(), loading = ref(false), error = ref('')
let generation = 0, controller: AbortController | undefined
function clear() { generation++; controller?.abort(); controller = undefined; report.value = undefined; loading.value = false; error.value = '' }
watch(() => [props.requirement, props.node, props.attempt], clear)
onBeforeUnmount(clear)
async function load() {
  const ticket = ++generation
  controller?.abort(); controller = new AbortController(); loading.value = true; error.value = ''
  try {
    const value = await workflowRuns.snapshotPartialReport(props.requirement, props.node, props.attempt, controller.signal)
    if (ticket === generation) report.value = value
  } catch (failure) {
    if (ticket === generation) error.value = userFacingError(failure, '阶段报告读取失败，请稍后重试。')
  } finally { if (ticket === generation) loading.value = false }
}
function download() {
  if (!report.value) return
  const url = URL.createObjectURL(new Blob([report.value.content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '代码审查阶段报告.md'; link.click(); URL.revokeObjectURL(url)
}
</script>
<template>
  <section class="workflow-snapshot-partial" aria-label="版本审查阶段报告">
    <h3>阶段报告</h3>
    <p class="muted">汇总当前计划中已完成的分析与复核，标明未完成范围；任务失败或取消后仍可查看。</p>
    <div class="workflow-inline"><button :disabled="loading" @click="load">{{ loading ? '正在读取阶段报告…' : report ? '刷新阶段报告' : '查看阶段报告' }}</button><button v-if="report" @click="download">下载阶段报告</button></div>
    <p v-if="error" role="alert" class="workflow-error">{{ error }}</p>
    <template v-if="report"><p>已分析 {{ report.analyzedUnits }} · 未完成 {{ report.pendingUnits }} · 排除 {{ report.excludedUnits }}</p><p class="muted">计划版本 {{ report.planRevision }} · {{ new Date(report.capturedAt).toLocaleString('zh-CN', { hour12: false }) }}</p><MarkdownDocument :content="report.content" :allow-images="false" /></template>
  </section>
</template>
