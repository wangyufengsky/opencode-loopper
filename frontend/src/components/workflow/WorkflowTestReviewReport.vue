<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowTestCoverageLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
type Case = { id: string; name: string; status: 'PASSED' | 'FAILED' | 'SKIPPED'; reportPath: string }
type Reference = { path: string; startLine: number; endLine: number; quote: string }
type Scenario = { key: string; title: string; path: string; status: string; reason: string; tests: Case[]; references: Reference[] }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim()
const statuses = ['COVERED', 'MISSING', 'INSUFFICIENT', 'FAILED', 'NOT_EXECUTED', 'EVIDENCE_INCOMPLETE']
const report = computed(() => {
  const value = props.content
  if (!object(value) || value.version !== 1 || value.type !== 'SOURCE_TEST_REVIEW' || !['PASS', 'REVISE'].includes(String(value.verdict)) || !text(value.reason) || typeof value.nativePassed !== 'boolean' || !Array.isArray(value.scenarios) || !value.scenarios.length) return null
  const keys = new Set<string>()
  for (const row of value.scenarios) {
    if (!object(row) || !text(row.key) || keys.has(row.key) || !text(row.title) || !text(row.path) || !text(row.reason) || !statuses.includes(String(row.status)) || !Array.isArray(row.tests) || !Array.isArray(row.references)) return null
    if (!row.tests.every(t => object(t) && text(t.id) && text(t.name) && text(t.reportPath) && ['PASSED', 'FAILED', 'SKIPPED'].includes(String(t.status)))) return null
    if (value.nativePassed && row.tests.some(t => t.status === 'FAILED')) return null
    if (!row.references.every(r => object(r) && text(r.path) && text(r.quote) && Number.isSafeInteger(r.startLine) && Number(r.startLine) >= 1 && Number.isSafeInteger(r.endLine) && Number(r.endLine) >= Number(r.startLine))) return null
    if (row.status === 'COVERED' && (row.assessment !== 'COVERED' || !row.tests.length || !row.references.length || !row.tests.every(t => t.status === 'PASSED') || value.inputUnchanged !== true)) return null
    keys.add(row.key)
  }
  if ((value.verdict === 'PASS') !== (value.nativePassed && value.scenarios.every(s => s.status === 'COVERED'))) return null
  return { passed: value.verdict === 'PASS', reason: value.reason, nativePassed: value.nativePassed, rows: value.scenarios as Scenario[] }
})
</script>
<template>
  <section class="workflow-test-review">
    <p v-if="!report" role="alert">场景复核报告无法读取，请重新读取原交付记录。</p>
    <template v-else>
      <StatusBadge :status="report.passed ? 'SUCCEEDED' : 'FAILED'" :label="report.passed ? '复核通过' : '需要修订'" />
      <p>{{ report.reason }}</p><p>原生测试：{{ report.nativePassed ? '通过' : '未通过' }}</p>
      <p class="workflow-inspector-hint">覆盖判断来自本节点的独立评审；执行结果和固定版本由程序核对。</p>
      <article v-for="(row, index) in report.rows" :key="row.key" class="workflow-binding">
        <h4>{{ index + 1 }}. {{ row.title }}</h4><p>{{ row.path }}</p><strong>{{ workflowTestCoverageLabel(row.status) }}</strong><p>{{ row.reason }}</p>
        <ul v-if="row.tests.length"><li v-for="test in row.tests" :key="test.id">{{ test.name }} · {{ test.status === 'PASSED' ? '执行通过' : test.status === 'FAILED' ? '执行失败' : '未执行（跳过）' }}</li></ul>
        <details v-if="row.references.length"><summary>测试代码依据（{{ row.references.length }} 条）</summary><article v-for="(ref, r) in row.references" :key="r"><p>{{ ref.path }} · 第 {{ ref.startLine }}–{{ ref.endLine }} 行</p><pre>{{ ref.quote }}</pre></article></details>
      </article>
    </template>
  </section>
</template>
<style scoped>
.workflow-test-review { min-width: 0; overflow-wrap: anywhere; }
.workflow-test-review pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.workflow-test-review h4 { margin-top: 0; }
.workflow-test-review .workflow-binding { gap: 8px; margin-top: 12px; }
.workflow-test-review .workflow-binding p, .workflow-test-review .workflow-binding h4, .workflow-test-review .workflow-binding ul { margin: 0; }
.workflow-test-review ul { padding-left: 20px; }
.workflow-test-review summary { cursor: pointer; }
</style>
