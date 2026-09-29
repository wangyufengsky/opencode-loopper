<script setup lang="ts">
import { computed } from 'vue'
import { documentFindingSeverityLabel, workflowHistorySideLabel } from '@/utils/displayLabels'
type Finding = { severity: string; side: string; line: number; title: string; detail: string; recommendation: string }
type Review = { unitId: string; summary: string; findings: Finding[]; limitations: string[] }
type Location = { unitId: string; commitSha: string; path: string }
type Dimension = { level: number; reason: string; evidenceIds: string[] }
const props = defineProps<{ content: unknown }>()
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every(s => typeof s === 'string')
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0
const report = computed(() => {
  const v = props.content
  if (!object(v) || v.version !== 1 || !object(v.source) || v.source.version !== 1 || v.source.type !== 'GIT_HISTORY' || !text(v.source.sha256) || !/^[a-f0-9]{64}$/.test(v.source.sha256)) return null
  if (v.type === 'HISTORY_REVIEW') {
    if (!integer(v.batchOrdinal) || !integer(v.batchCount) || v.batchOrdinal >= v.batchCount || !Array.isArray(v.reviews) || v.reviews.length < 1 || v.reviews.length > 12 || !Array.isArray(v.locations) || v.locations.length !== v.reviews.length) return null
    const locations = v.locations as Location[], reviews = v.reviews as Review[]
    if (!locations.every(l => l && text(l.unitId) && typeof l.path === 'string' && typeof l.commitSha === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(l.commitSha)) || new Set(locations.map(l => l.unitId)).size !== reviews.length) return null
    if (!reviews.every(r => r && text(r.unitId) && text(r.summary) && strings(r.limitations) && r.limitations.length <= 32 && Array.isArray(r.findings) && r.findings.length <= 32
      && r.findings.every(f => f && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(f.severity) && ['BEFORE', 'AFTER'].includes(f.side) && integer(f.line) && f.line > 0 && text(f.title) && text(f.detail) && text(f.recommendation)))
      || new Set(reviews.map(r => r.unitId)).size !== reviews.length || reviews.some(r => !locations.some(l => l.unitId === r.unitId))) return null
    return { kind: 'review' as const, batch: v.batchOrdinal + 1, total: v.batchCount, reviews: reviews.map(r => ({ ...r, location: locations.find(l => l.unitId === r.unitId)! })) }
  }
  if (v.type !== 'HISTORY_CONTRIBUTION' || !object(v.person) || !object(v.person.author) || !object(v.assessment) || !strings(v.reviewAttempts) || v.reviewAttempts.length === 0) return null
  const person = v.person, author = v.person.author, assessment = v.assessment
  if (!text(author.name) || !text(author.identity) || typeof author.email !== 'string' || typeof author.robot !== 'boolean' || author.identity !== assessment.identity || !text(assessment.summary)
    || !strings(person.commits) || !strings(person.evidenceIds) || ![person.rawLines, person.effectiveLines].every(n => typeof n === 'number' && Number.isFinite(n) && n >= 0)) return null
  const titles = [['value', '代码价值'], ['difficulty', '必要技术难度'], ['quality', '质量与验证证据'], ['maintenance', '工程维护']] as const
  if (!titles.every(([key]) => { const d = assessment[key]; return object(d) && integer(d.level) && d.level <= 4 && text(d.reason) && strings(d.evidenceIds) && d.evidenceIds.length > 0 && d.evidenceIds.every(id => (person.evidenceIds as string[]).includes(id)) })) return null
  return { kind: 'contribution' as const, name: author.name, email: author.email, robot: author.robot, commits: person.commits.length,
    raw: Number(person.rawLines).toLocaleString('zh-CN', { maximumFractionDigits: 2 }), effective: Number(person.effectiveLines).toLocaleString('zh-CN', { maximumFractionDigits: 2 }), summary: assessment.summary,
    dimensions: titles.map(([key, title]) => ({ title, ...assessment[key] as Dimension })) }
})
</script>
<template><div class="workflow-history-analysis-report">
  <p v-if="!report" role="alert">历史分析结果无法读取，请重新读取本次交付物。</p>
  <template v-else-if="report.kind === 'review'">
    <p><strong>第 {{ report.batch }} / {{ report.total }} 批历史审查</strong> · {{ report.reviews.length }} 项变更</p>
    <p>静态审查记录；未运行的测试不能视为通过。</p>
    <details v-for="(item, index) in report.reviews" :key="item.unitId">
      <summary>{{ item.location.path || '无文件变更' }} · 证据项 {{ index + 1 }} · {{ item.findings.length }} 个问题</summary>
      <p>提交：<code>{{ item.location.commitSha }}</code></p><p>{{ item.summary }}</p>
      <article v-for="(finding, i) in item.findings" :key="i"><h4>{{ finding.title }}</h4><p>{{ documentFindingSeverityLabel(finding.severity) }}影响 · {{ workflowHistorySideLabel(finding.side) }}第 {{ finding.line }} 行</p><p>{{ finding.detail }}</p><p>建议：{{ finding.recommendation }}</p></article>
      <ul v-if="item.limitations.length"><li v-for="(limitation, i) in item.limitations" :key="i">{{ limitation }}</li></ul>
    </details>
  </template>
  <template v-else>
    <h4>{{ report.name }} · 个人贡献评价</h4><p>{{ report.email }}<span v-if="report.robot"> · 机器人，报告不参与排名</span></p>
    <p>{{ report.commits }} 个提交 · 变更 {{ report.raw }} 行 · 有效 {{ report.effective }} 行</p><p>{{ report.summary }}</p>
    <p>以下为有依据的四维等级；完整报告另行计算分数与排名。</p>
    <article v-for="dimension in report.dimensions" :key="dimension.title"><h4>{{ dimension.title }} · {{ dimension.level }} / 4</h4><p>{{ dimension.reason }}</p><small>引用 {{ dimension.evidenceIds.length }} 项本人证据</small></article>
  </template>
</div></template>
<style scoped>
.workflow-history-analysis-report { overflow-wrap: anywhere; }
details, article { padding: .65rem 0; border-bottom: 1px solid var(--color-border-default); }
summary { cursor: pointer; } h4 { margin: .3rem 0; } code { word-break: break-all; }
</style>
