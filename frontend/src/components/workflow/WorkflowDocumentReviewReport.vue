<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { requirementConclusionLabel, documentFindingKindLabel, documentFindingSeverityLabel } from '@/utils/displayLabels'
import type { DocumentRequirementDetail, RequirementCodeReference } from '@/types/domain'
const props = defineProps<{ content: unknown; review?: boolean }>()
type Source = { fileId: string; section: number }
type Entry = { title: string; statement: string; sources: Source[]; issues: string[]; assessment: NonNullable<DocumentRequirementDetail['assessment']> }
type Finding = { key: string; kind: string; severity: string; title: string; trigger: string; impact: string; recommendation: string; evidence: RequirementCodeReference[] }
type Skipped = { source: Source; reason: string }
type Correction = { requirementKey: string | null; findingKey: string | null; source: Source | null; detail: string }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
const source = (value: unknown): value is Source => !!value && typeof value === 'object' && 'fileId' in value && typeof value.fileId === 'string' && /^DOC-[1-9][0-9]?$/.test(value.fileId) && 'section' in value && Number.isSafeInteger(value.section) && Number(value.section) > 0
const sourceLabel = (value: Source) => `文档 ${value.fileId.slice(4)} · 第 ${value.section} 章`
function references(value: unknown): value is RequirementCodeReference[] {
  return Array.isArray(value) && value.every(item => item && typeof item.path === 'string' && Number.isSafeInteger(item.startLine) && item.startLine > 0 && Number.isSafeInteger(item.endLine) && item.endLine >= item.startLine && typeof item.quote === 'string')
}
const assessment = computed(() => {
  if (props.review) return null
  const value = props.content as { entries?: unknown; findings?: unknown; skippedSections?: unknown; limitations?: unknown } | null
  if (!value || !Array.isArray(value.entries) || !Array.isArray(value.findings) || !Array.isArray(value.skippedSections) || !strings(value.limitations)) return null
  if (!value.entries.every(item => item && typeof item.title === 'string' && typeof item.statement === 'string' && Array.isArray(item.sources) && item.sources.length && item.sources.every(source) && strings(item.issues) && item.assessment && ['SATISFIED', 'PARTIAL', 'INCORRECT', 'NOT_IMPLEMENTED', 'UNDETERMINED'].includes(item.assessment.conclusion) && typeof item.assessment.rationale === 'string' && references(item.assessment.evidence) && strings(item.assessment.checkedPaths) && strings(item.assessment.limitations) && typeof item.assessment.testSourceCoverage === 'string' && (!item.issues.length || item.assessment.conclusion === 'UNDETERMINED') && (item.assessment.conclusion === 'UNDETERMINED' || item.assessment.evidence.length))) return null
  if (!value.findings.every(item => item && typeof item.key === 'string' && ['DEFECT', 'VALIDATION_GAP', 'SUGGESTION'].includes(item.kind) && ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].includes(item.severity) && typeof item.title === 'string' && typeof item.trigger === 'string' && typeof item.impact === 'string' && typeof item.recommendation === 'string' && references(item.evidence))) return null
  if (!value.skippedSections.every(item => item && source(item.source) && typeof item.reason === 'string')) return null
  return { entries: value.entries as Entry[], findings: value.findings as Finding[], skipped: value.skippedSections as Skipped[], limitations: value.limitations }
})
const opinion = computed(() => {
  if (!props.review) return null
  const value = props.content as { approved?: unknown; reviewedRequirementKeys?: unknown; reviewedFindingKeys?: unknown; checkedSections?: unknown; corrections?: unknown } | null
  if (!value || typeof value.approved !== 'boolean' || !strings(value.reviewedRequirementKeys) || !strings(value.reviewedFindingKeys) || !Array.isArray(value.checkedSections) || !value.checkedSections.length || !value.checkedSections.every(source) || !Array.isArray(value.corrections) || value.approved !== (value.corrections.length === 0)) return null
  if (!value.corrections.every(item => item && typeof item.detail === 'string' && (typeof item.requirementKey === 'string' || typeof item.findingKey === 'string' || source(item.source)) && (!item.source || source(item.source)))) return null
  return { approved: value.approved, requirements: value.reviewedRequirementKeys.length, findings: value.reviewedFindingKeys.length, sections: value.checkedSections as Source[], corrections: value.corrections as Correction[] }
})
</script>
<template>
  <div class="workflow-document-review-report">
    <template v-if="assessment"><p class="workflow-inspector-hint">静态代码评审 · 未运行测试</p><p>{{ assessment.entries.length }} 项需求结论 · {{ assessment.findings.length }} 项问题</p>
      <article v-for="(entry, index) in assessment.entries" :key="index" class="workflow-binding"><h4>{{ entry.title }}</h4><StatusBadge :status="entry.assessment.conclusion === 'SATISFIED' ? 'PASS' : 'PENDING'" :label="requirementConclusionLabel(entry.assessment.conclusion)" /><p>{{ entry.statement }}</p><p>{{ entry.assessment.rationale }}</p><p>{{ entry.sources.map(sourceLabel).join('；') }}</p><ul v-if="entry.issues.length"><li v-for="(issue, i) in entry.issues" :key="i">待澄清：{{ issue }}</li></ul>
        <details><summary>依据与局限</summary><article v-for="(ref, i) in entry.assessment.evidence" :key="i"><strong>{{ ref.path }} · 第 {{ ref.startLine }}–{{ ref.endLine }} 行</strong><pre>{{ ref.quote }}</pre></article><p v-if="entry.assessment.checkedPaths.length">已检查：{{ entry.assessment.checkedPaths.join('、') }}</p><p v-if="entry.assessment.missingEntryEvidence">未实现依据：{{ entry.assessment.missingEntryEvidence }}</p><p>测试源码情况：{{ entry.assessment.testSourceCoverage }}</p><ul><li v-for="(item, i) in entry.assessment.limitations" :key="i">{{ item }}</li></ul></details>
      </article>
      <details v-if="assessment.findings.length"><summary>问题明细</summary><article v-for="finding in assessment.findings" :key="finding.key"><h4>{{ finding.title }}</h4><p>{{ documentFindingKindLabel(finding.kind) }} · {{ documentFindingSeverityLabel(finding.severity) }}影响</p><p>触发条件：{{ finding.trigger }}</p><p>影响：{{ finding.impact }}</p><p>建议：{{ finding.recommendation }}</p><div v-for="(ref, i) in finding.evidence" :key="i"><strong>{{ ref.path }} · 第 {{ ref.startLine }}–{{ ref.endLine }} 行</strong><pre>{{ ref.quote }}</pre></div></article></details>
      <details v-if="assessment.skipped.length"><summary>未适用章节（{{ assessment.skipped.length }}）</summary><p v-for="(item, i) in assessment.skipped" :key="i">{{ sourceLabel(item.source) }}：{{ item.reason }}</p></details>
      <details v-if="assessment.limitations.length"><summary>总体局限</summary><ul><li v-for="(item, i) in assessment.limitations" :key="i">{{ item }}</li></ul></details>
    </template>
    <template v-else-if="opinion"><StatusBadge :status="opinion.approved ? 'PASS' : 'FAILED'" :label="opinion.approved ? '复核通过' : '需要返修'" /><p>独立核对 {{ opinion.requirements }} 项结论、{{ opinion.findings }} 项问题和 {{ opinion.sections.length }} 个章节。</p><p class="workflow-inspector-hint">复核意见不代表已运行测试。</p><article v-for="(correction, i) in opinion.corrections" :key="i" class="workflow-binding"><strong>修正意见 {{ i + 1 }}</strong><p v-if="correction.requirementKey && /^RQ-[1-9][0-9]*$/.test(correction.requirementKey)">关联需求 {{ correction.requirementKey.slice(3) }}</p><p v-if="correction.source">{{ sourceLabel(correction.source) }}</p><p>{{ correction.detail }}</p></article><details><summary>已核对原文</summary><p v-for="(item, i) in opinion.sections" :key="i">{{ sourceLabel(item) }}</p></details></template>
    <p v-else role="alert">需求代码评审格式无法读取，请重新读取本次交付物。</p>
  </div>
</template>
<style scoped>
.workflow-document-review-report { min-width: 0; overflow-wrap: anywhere; }pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow: auto; }
</style>
