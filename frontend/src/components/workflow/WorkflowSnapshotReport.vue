<script setup lang="ts">
import { computed } from 'vue'
import { documentFindingSeverityLabel, workflowSnapshotAttributionLabel, workflowSnapshotVerdictLabel } from '@/utils/displayLabels'
type Reuse = { sourceRequirementId: string; sourceAttemptId: string; sourceTitle: string; sourceNodeTitle: string; sourceDeliverySha256: string }
type Reference = { version: string; path: string; blob: string; startLine: number; endLine: number; quote: string }
type Finding = { key: string; severity: string; title: string; trigger: string; behavior: string; recommendation: string; attribution: string; evidence: Reference[] }
type Problem = { key: string; title: string }
type Coverage = { unitId: string; conclusion: string; evidence: Reference[]; limitations: string[] }
type Decision = { findingKey: string; verdict: string; reason: string; duplicateOf: string | null; evidence: Reference[] }
const props = defineProps<{ content: unknown }>()
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0
const strings = (v: unknown): v is string[] => Array.isArray(v) && v.length <= 64 && v.every(text)
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const sha = (v: unknown) => typeof v === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v)
const references = (v: unknown): v is Reference[] => Array.isArray(v) && v.length <= 64 && v.every(r => object(r) && sha(r.version) && sha(r.blob) && text(r.path) && integer(r.startLine) && r.startLine > 0 && integer(r.endLine) && r.endLine >= r.startLine && text(r.quote))
const problems = (v: unknown): v is Problem[] => Array.isArray(v) && new Set(v.map(f => f?.key)).size === v.length && v.every(f => object(f) && text(f.key) && text(f.title))
const findings = (v: unknown): v is Finding[] => Array.isArray(v) && new Set(v.map(f => f?.key)).size === v.length && v.every(f => object(f) && text(f.key) && text(f.title) && text(f.trigger) && text(f.behavior) && text(f.recommendation) && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(String(f.severity)) && ['CHANGE_RELATED', 'EXISTING', 'UNDETERMINED'].includes(String(f.attribution)) && references(f.evidence) && f.evidence.length > 0)
const report = computed(() => {
  const v = props.content
  if (!object(v) || v.version !== 1 || !object(v.source) || v.source.version !== 1 || v.source.type !== 'REVIEW_SOURCE' || typeof v.source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(v.source.sha256) || !object(v.claims) || !strings(v.claims.limitations)) return null
  const c = v.claims
  if (v.type === 'SNAPSHOT_ANALYSIS') {
    if (!integer(v.batchOrdinal) || !integer(v.batchCount) || v.batchOrdinal >= v.batchCount || !findings(c.findings) || !Array.isArray(c.coverage) || c.coverage.length < 1 || c.coverage.length > 64 || !Array.isArray(v.locations) || v.locations.length !== c.coverage.length || !Array.isArray(c.supplements) || c.supplements.length) return null
    const coverage = c.coverage as Coverage[], locations = v.locations as { unitId: string; path: string }[]
    if (!coverage.every(item => item && text(item.unitId) && text(item.conclusion) && references(item.evidence) && strings(item.limitations)) || !locations.every(item => item && text(item.unitId) && text(item.path)) || new Set(coverage.map(item => item.unitId)).size !== coverage.length || new Set(locations.map(item => item.unitId)).size !== locations.length || coverage.some(item => !locations.some(l => l.unitId === item.unitId))) return null
    const reuse = v.reuse == null ? null : v.reuse
    if (reuse !== null && (!object(reuse) || !text(reuse.sourceRequirementId) || !text(reuse.sourceAttemptId) || !text(reuse.sourceTitle) || !text(reuse.sourceNodeTitle) || typeof reuse.sourceDeliverySha256 !== 'string' || !/^[a-f0-9]{64}$/.test(reuse.sourceDeliverySha256) || c.findings.length || (c.limitations as string[]).length || coverage.some(item => item.evidence.length || item.limitations.length))) return null
    return { reuse: reuse as Reuse | null, kind: 'analysis' as const, batch: v.batchOrdinal + 1, total: v.batchCount, findings: c.findings, limitations: c.limitations as string[], coverage: coverage.map(item => ({ ...item, path: locations.find(l => l.unitId === item.unitId)!.path })) }
  }
  if (v.type !== 'SNAPSHOT_REVIEW' || !text(v.analysisAttempt) || !problems(v.findings) || !v.findings.length || !Array.isArray(c.checkedUnitIds) || !c.checkedUnitIds.every(text) || new Set(c.checkedUnitIds).size !== c.checkedUnitIds.length || !references(c.evidence) || !text(c.conclusion) || !Array.isArray(c.decisions) || c.decisions.length !== v.findings.length) return null
  const decisions = c.decisions as Decision[], known = v.findings
  if (!decisions.every(d => d && text(d.findingKey) && text(d.reason) && ['SUPPORTED', 'UNDETERMINED', 'DISMISSED', 'DUPLICATE'].includes(d.verdict) && references(d.evidence) && (d.verdict === 'UNDETERMINED' || d.evidence.length > 0) && (d.verdict === 'DUPLICATE' ? text(d.duplicateOf) && d.duplicateOf !== d.findingKey : d.duplicateOf == null)) || new Set(decisions.map(d => d.findingKey)).size !== known.length || known.some(f => !decisions.some(d => d.findingKey === f.key))) return null
  return { kind: 'review' as const, conclusion: c.conclusion, limitations: c.limitations as string[], decisions: decisions.map(d => ({ ...d, finding: known.find(f => f.key === d.findingKey)! })) }
})
</script>
<template><div class="workflow-snapshot-report">
  <p v-if="!report" role="alert">版本审查结果无法读取，请重新读取本次交付物。</p>
  <template v-else>
    <p v-if="report.kind === 'analysis'"><strong>第 {{ report.batch }} / {{ report.total }} 批版本分析</strong> · {{ report.coverage.length }} 个片段 · {{ report.findings.length }} 个候选问题</p>
    <template v-else><h4>候选问题独立复核</h4><p>{{ report.conclusion }}</p></template>
    <p>静态代码审查；未运行的测试不能视为通过。</p>
    <template v-if="report.kind === 'analysis'">
      <p v-if="report.reuse" class="reuse-provenance">已复用历史分析，本次没有创建模型会话。来源：<a :href="`/requirements/${encodeURIComponent(report.reuse.sourceRequirementId)}`">{{ report.reuse.sourceTitle }}</a> · {{ report.reuse.sourceNodeTitle }}</p>
      <p v-if="report.findings.length === 0">本批未提出候选问题，未进行问题独立复核；不代表代码没有缺陷。</p>
      <article v-for="finding in report.findings" :key="finding.key"><h4>{{ finding.title }}</h4><p>{{ documentFindingSeverityLabel(finding.severity) }}影响 · {{ workflowSnapshotAttributionLabel(finding.attribution) }}</p><p>触发条件：{{ finding.trigger }}</p><p>{{ finding.behavior }}</p><p>建议：{{ finding.recommendation }}</p><details v-for="(r, i) in finding.evidence" :key="i"><summary>{{ r.path }} · 第 {{ r.startLine }}–{{ r.endLine }} 行</summary><p>版本：<code>{{ r.version }}</code></p><pre>{{ r.quote }}</pre></details></article>
      <details><summary>本批片段覆盖</summary><article v-for="(item, i) in report.coverage" :key="item.unitId"><h4>{{ item.path }} · 片段 {{ i + 1 }}</h4><p>{{ item.conclusion }}</p><ul v-if="item.limitations.length"><li v-for="(limitation, j) in item.limitations" :key="j">{{ limitation }}</li></ul></article></details>
    </template>
    <article v-for="decision in report.kind === 'review' ? report.decisions : []" :key="decision.findingKey"><h4>{{ decision.finding.title }}</h4><p><strong>{{ workflowSnapshotVerdictLabel(decision.verdict) }}</strong></p><p>{{ decision.reason }}</p><p v-if="decision.duplicateOf">已关联对应问题，完整报告保留合并关系。</p><details v-for="(r, i) in decision.evidence" :key="i"><summary>{{ r.path }} · 第 {{ r.startLine }}–{{ r.endLine }} 行</summary><p>版本：<code>{{ r.version }}</code></p><pre>{{ r.quote }}</pre></details></article>
    <ul v-if="report.limitations.length"><li v-for="(limitation, i) in report.limitations" :key="i">{{ limitation }}</li></ul>
  </template>
</div></template>
<style scoped>
.workflow-snapshot-report { overflow-wrap: anywhere; }
.reuse-provenance a { text-decoration: underline; text-underline-offset: .15em; }
article, details { padding: .65rem 0; border-bottom: 1px solid var(--color-border-default); }
summary { cursor: pointer; } h4 { margin: .3rem 0; } code { word-break: break-all; } pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: .5rem 0; }
</style>
