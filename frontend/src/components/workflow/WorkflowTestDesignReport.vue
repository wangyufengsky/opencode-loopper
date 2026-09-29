<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ content: unknown }>()
type Reference = { path: string; startLine: number; endLine: number; quote: string }
type Scenario = { key: string; path: string; category: keyof typeof categories; title: string; steps: string[]; expected: string; references: Reference[] }
const categories = { NORMAL: '正常', BOUNDARY: '边界', ERROR: '异常', BRANCH: '关键分支' }
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(text)
const report = computed(() => {
  const value = props.content as { version?: unknown; type?: unknown; design?: { title?: unknown; summary?: unknown; scenarios?: unknown; limitations?: unknown } } | null
  const design = value?.design
  if (value?.version !== 1 || value.type !== 'SOURCE_TEST_DESIGN' || !design || !text(design.title) || !text(design.summary) || !strings(design.limitations) || !Array.isArray(design.scenarios) || !design.scenarios.length) return null
  const keys = new Set<string>()
  for (const item of design.scenarios) {
    if (!item || !text(item.key) || keys.has(item.key) || !text(item.path) || !Object.prototype.hasOwnProperty.call(categories, item.category) || !text(item.title) || !strings(item.steps) || !item.steps.length || !text(item.expected) || !Array.isArray(item.references) || !item.references.length) return null
    if (!item.references.every((ref: Reference) => ref && ref.path === item.path && Number.isSafeInteger(ref.startLine) && ref.startLine >= 1 && Number.isSafeInteger(ref.endLine) && ref.endLine >= ref.startLine && text(ref.quote))) return null
    keys.add(item.key)
  }
  return { title: design.title, summary: design.summary, scenarios: design.scenarios as Scenario[], limitations: design.limitations }
})
</script>
<template>
  <div class="workflow-test-design-report">
    <template v-if="report">
      <h3>{{ report.title }}</h3><p>{{ report.summary }}</p><p class="workflow-inspector-hint">{{ report.scenarios.length }} 个测试场景 · 尚未执行测试</p>
      <article v-for="(scenario, index) in report.scenarios" :key="scenario.key" class="workflow-binding">
        <h4>{{ index + 1 }}. {{ scenario.title }}</h4><p>{{ categories[scenario.category] }} · {{ scenario.path }}</p>
        <strong>测试步骤</strong><ol><li v-for="(step, stepIndex) in scenario.steps" :key="stepIndex">{{ step }}</li></ol>
        <strong>预期结果</strong><p class="workflow-test-expected">{{ scenario.expected }}</p>
        <details><summary>源码依据（{{ scenario.references.length }} 条）</summary><article v-for="(ref, refIndex) in scenario.references" :key="refIndex"><p>{{ ref.path }} · 第 {{ ref.startLine }}–{{ ref.endLine }} 行</p><pre>{{ ref.quote }}</pre></article></details>
      </article>
      <details v-if="report.limitations.length"><summary>局限与待确认事项</summary><ul><li v-for="(item, index) in report.limitations" :key="index">{{ item }}</li></ul></details>
    </template>
    <p v-else role="alert">单测场景交付格式无法读取，请重新读取本次交付物。</p>
  </div>
</template>
<style scoped>
.workflow-test-design-report { min-width: 0; overflow-wrap: anywhere; }
.workflow-test-design-report pre, .workflow-test-expected { white-space: pre-wrap; overflow-wrap: anywhere; }
.workflow-test-design-report h4 { margin-top: 0; }
</style>
