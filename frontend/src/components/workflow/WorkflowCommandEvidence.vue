<script setup lang="ts">
import type { WorkflowCommandEvidence } from '@/types/domain'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
import { computed, ref } from 'vue'
const props = defineProps<{ evidence: WorkflowCommandEvidence; terminal: boolean; repository?: boolean; history?: boolean; reviewSource?: boolean }>()
const selectedReport = ref<string | null>(null), rawOpen = ref(false), commandOpen = ref(false)
const preparationSteps = computed(() => (props.evidence.request?.preparations ?? []).map((step, index) => ({
  step, result: props.evidence.result?.preparations?.[index],
})))
const nativeFiles = computed(() => {
  const value = props.evidence.nativeReport as { files?: unknown } | null
  if (!value || !Array.isArray(value.files) || value.files.length > 128) return []
  return value.files.filter((f): f is { path: string; content: string } => !!f && typeof f.path === 'string' && typeof f.content === 'string')
})
</script>
<template><div class="workflow-command-evidence">
  <section v-if="preparationSteps.length" aria-label="依赖准备结果"><h4>依赖准备</h4>
    <article v-for="({ step, result }, index) in preparationSteps" :key="index">
      <h5>准备步骤 {{ index + 1 }}</h5>
      <template v-if="result">
        <p>{{ result.launched ? '准备命令已启动。' : '准备命令没有启动。' }}{{ result.stopConfirmed ? '已取得停止证明。' : '停止尚未确认。' }}</p>
        <p v-if="result.exitCode !== null">进程退出码：{{ result.exitCode }}</p>
        <p v-if="result.cancelled">准备已取消。</p><p v-if="result.timedOut">准备超过执行时限。</p><p v-if="result.outputTruncated">保存的输出不完整。</p>
        <p v-if="result.error" role="alert">{{ workflowReasonLabel(result.error) }}</p>
      </template><p v-else>{{ evidence.result || terminal ? '本步骤未执行。' : '尚未取得执行结果。' }}</p>
      <details><summary>查看准备命令和输出</summary><pre class="workflow-command-output">{{ JSON.stringify(step.argv, null, 2) }}</pre>
        <pre v-if="result?.output" class="workflow-command-output" :aria-label="`准备步骤 ${index + 1} 的输出`">{{ result.output }}</pre>
      </details>
    </article>
  </section>
  <template v-if="evidence.request"><template v-if="repository"><h4>{{ reviewSource ? '版本审查采集' : history ? 'Git 历史采集' : '分支代码采集' }}</h4><details @toggle="commandOpen = ($event.target as HTMLDetailsElement).open"><summary>查看采集命令</summary><CodeMergeEditor v-if="commandOpen" :model-value="JSON.stringify(evidence.request.argv, null, 2)" language="json" readonly /></details></template><template v-else><h4>检查命令</h4><CodeMergeEditor :model-value="JSON.stringify(evidence.request.argv, null, 2)" language="json" readonly /></template><p>{{ preparationSteps.length ? '总执行时限（包含依赖准备）' : '执行时限' }}：{{ evidence.request.timeoutSeconds }} 秒</p></template>
  <p v-else>本次尝试尚未准备执行命令。</p>
  <template v-if="evidence.result"><p>{{ repository ? (evidence.result.launched ? (history ? '历史采集已启动。' : '代码采集已启动。') : (history ? '历史采集没有启动。' : '代码采集没有启动。')) : (evidence.result.launched ? '检查命令已启动。' : '检查命令没有启动。') }}{{ evidence.result.stopConfirmed ? '已取得停止证明。' : '停止尚未确认。' }}</p>
    <p v-if="evidence.result.exitCode !== null">进程退出码：{{ evidence.result.exitCode }}</p>
    <p v-if="evidence.result.timedOut">命令超过执行时限。</p><p v-if="evidence.result.cancelled">{{ repository ? '本次采集已取消。' : '本次检查已取消。' }}</p><p v-if="evidence.result.outputTruncated">保存的输出不完整。</p>
    <p v-if="evidence.result.error" role="alert">{{ workflowReasonLabel(evidence.result.error) }}</p>
    <pre v-if="evidence.result.output" class="workflow-command-output" aria-label="已保存命令输出">{{ evidence.result.output }}</pre>
  </template><p v-else-if="!terminal">尚未取得完整执行结果；命令结束并确认停止后保存输出。</p>
  <section v-if="nativeFiles.length"><h4>已保存的原生测试报告</h4><article v-for="file in nativeFiles" :key="file.path">
    <button :aria-expanded="selectedReport === file.path" @click="selectedReport = selectedReport === file.path ? null : file.path">{{ file.path }}</button>
    <CodeMergeEditor v-if="selectedReport === file.path" :model-value="file.content" :language="file.path.endsWith('.json') ? 'json' : 'plain'" readonly />
  </article></section>
  <details @toggle="rawOpen = ($event.target as HTMLDetailsElement).open"><summary>原始执行记录</summary><CodeMergeEditor v-if="rawOpen" :model-value="JSON.stringify(evidence, null, 2)" language="json" readonly /></details>
</div></template>
