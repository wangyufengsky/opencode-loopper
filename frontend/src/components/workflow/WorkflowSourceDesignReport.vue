<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
const props = defineProps<{ content: unknown; review?: boolean }>()
type Reference = { path: string; startLine: number; endLine: number; quote: string }
type Section = { key: string; title: string; markdown: string; paths: string[]; references: Reference[] }
type Issue = { sectionKey: string; detail: string; recommendation: string }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
function references(value: unknown): value is Reference[] {
  return Array.isArray(value) && value.length > 0 && value.every(item => item && typeof item.path === 'string' && Number.isSafeInteger(item.startLine) && item.startLine >= 1 && Number.isSafeInteger(item.endLine) && item.endLine >= item.startLine && typeof item.quote === 'string')
}
const design = computed(() => {
  if (props.review) return null
  const value = props.content as { title?: unknown; summary?: unknown; sections?: unknown; limitations?: unknown } | null
  if (!value || typeof value.title !== 'string' || typeof value.summary !== 'string' || !strings(value.limitations) || !Array.isArray(value.sections) || !value.sections.length) return null
  if (!value.sections.every(item => item && typeof item.key === 'string' && typeof item.title === 'string' && typeof item.markdown === 'string' && strings(item.paths) && references(item.references))) return null
  return { title: value.title, summary: value.summary, sections: value.sections as Section[], limitations: value.limitations }
})
const opinion = computed(() => {
  if (!props.review) return null
  const value = props.content as { verdict?: unknown; reason?: unknown; checkedPaths?: unknown; references?: unknown; issues?: unknown } | null
  if (!value || (value.verdict !== 'PASS' && value.verdict !== 'REVISE') || typeof value.reason !== 'string' || !strings(value.checkedPaths) || !value.checkedPaths.length || !references(value.references) || !Array.isArray(value.issues)) return null
  if ((value.verdict === 'PASS') !== (value.issues.length === 0) || !value.issues.every(item => item && typeof item.sectionKey === 'string' && typeof item.detail === 'string' && typeof item.recommendation === 'string')) return null
  return { verdict: value.verdict, reason: value.reason, paths: value.checkedPaths, references: value.references, issues: value.issues as Issue[] }
})
const citations = computed(() => opinion.value?.references || design.value?.sections.flatMap(section => section.references) || [])
</script>
<template>
  <div class="workflow-source-design-report">
    <template v-if="design"><h3>{{ design.title }}</h3><p>{{ design.summary }}</p><article v-for="section in design.sections" :key="section.key"><h4>{{ section.title }}</h4><p class="workflow-inspector-hint">{{ section.paths.join('、') }}</p><MarkdownDocument :content="section.markdown" :allow-images="false" /></article><details v-if="design.limitations.length"><summary>局限与未知项</summary><ul><li v-for="(item, index) in design.limitations" :key="index">{{ item }}</li></ul></details></template>
    <template v-else-if="opinion"><StatusBadge :status="opinion.verdict === 'PASS' ? 'PASS' : 'FAILED'" :label="opinion.verdict === 'PASS' ? '复核通过' : '需要返修'" /><p>{{ opinion.reason }}</p><p>已复核 {{ opinion.paths.length }} 个源码文件</p><article v-for="(issue, index) in opinion.issues" :key="index" class="workflow-binding"><strong>问题 {{ index + 1 }} · {{ issue.sectionKey }}</strong><p>{{ issue.detail }}</p><p>修订建议：{{ issue.recommendation }}</p></article></template>
    <p v-else role="alert">专业设计交付格式无法读取，请重新读取本次交付物。</p>
    <details v-if="citations.length"><summary>源码依据（{{ citations.length }} 条）</summary><article v-for="(reference, index) in citations" :key="index"><strong>{{ reference.path }} · 第 {{ reference.startLine }}–{{ reference.endLine }} 行</strong><pre>{{ reference.quote }}</pre></article></details>
  </div>
</template>
