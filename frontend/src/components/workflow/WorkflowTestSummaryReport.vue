<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
const props = defineProps<{ content: unknown }>()
const policies: Record<string, string> = { NONE: '不要求复核', SINGLE: '每批一份独立复核', DUAL: '每批两位独立角色复核' }
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  if (!v || v.version !== 1 || v.type !== 'SOURCE_TEST_SUMMARY'
    || !['complete', 'passed', 'testPassed', 'reviewSatisfied'].every(k => typeof v[k] === 'boolean')
    || !['sourceCount', 'coveredSourceCount', 'moduleCount', 'batchCount', 'reviewedBatchCount'].every(k => Number.isSafeInteger(v[k]) && Number(v[k]) >= 0)
    || !Number(v.sourceCount) || !Number(v.moduleCount) || !Number(v.batchCount) || Number(v.coveredSourceCount) > Number(v.sourceCount)
    || typeof v.reviewPolicy !== 'string' || !policies[v.reviewPolicy] || Number(v.reviewedBatchCount) > Number(v.batchCount)
    || !Array.isArray(v.modules) || !v.modules.length || v.modules.length > Number(v.moduleCount)
    || !Array.isArray(v.batches) || v.batches.length !== v.batchCount || v.batches.length > 61 * 61) return null
  const modules: { root: string; passed: boolean; executed: number; failed: number; skipped: number }[] = []
  for (const m of v.modules) {
    const c = m?.counts
    if (!m || typeof m.root !== 'string' || !m.root || typeof m.passed !== 'boolean' || !c
      || !['total', 'passed', 'failed', 'skipped'].every(k => Number.isSafeInteger(c[k]) && c[k] >= 0)
      || c.total !== c.passed + c.failed + c.skipped || modules.some(item => item.root === m.root)) return null
    if (m.passed && (c.passed < 1 || c.failed !== 0)) return null
    modules.push({ root: m.root, passed: m.passed, executed: c.passed + c.failed, failed: c.failed, skipped: c.skipped })
  }
  const batches: { module: string; count: number; satisfied: boolean; opinions: number }[] = []
  for (const b of v.batches) {
    if (!b || !modules.some(m => m.root === b.moduleRoot) || !Number.isSafeInteger(b.scenarioCount) || b.scenarioCount < 1 || b.scenarioCount > 64
      || typeof b.reviewSatisfied !== 'boolean' || !Array.isArray(b.reviews) || b.reviews.length > 2) return null
    batches.push({ module: b.moduleRoot, count: b.scenarioCount, satisfied: b.reviewSatisfied, opinions: b.reviews.length })
  }
  if (v.complete && (v.coveredSourceCount !== v.sourceCount || modules.length !== v.moduleCount)
    || v.testPassed !== modules.every(m => m.passed) || v.reviewSatisfied !== batches.every(b => b.satisfied)
    || v.passed !== (v.complete && v.testPassed && v.reviewSatisfied)) return null
  return { passed: v.passed, covered: v.coveredSourceCount, total: v.sourceCount, policy: policies[v.reviewPolicy], reviewRequired: v.reviewPolicy !== 'NONE', modules, batches }
})
</script>
<template><div class="workflow-test-summary-report">
  <p v-if="!report" role="alert">单测汇总报告无法读取，请重新加载本次交付物。</p>
  <template v-else>
    <StatusBadge :status="report.passed ? 'PASS' : 'FAILED'" :label="report.passed ? '单测汇总通过' : '单测汇总未通过'" />
    <p>源码覆盖 {{ report.covered }} / {{ report.total }} 个文件。全部模块使用同一份最终代码。</p>
    <p>完成策略：{{ report.policy }}。</p>
    <div v-for="module in report.modules" :key="module.root"><strong>{{ module.root === '.' ? '项目根目录' : module.root }}</strong><p>{{ module.passed ? '测试通过' : '测试未通过' }} · 实际执行 {{ module.executed }} 项，失败 {{ module.failed }} 项，跳过 {{ module.skipped }} 项。</p></div>
    <details><summary>查看 {{ report.batches.length }} 批场景的复核情况</summary><p v-for="(batch, index) in report.batches" :key="index">第 {{ index + 1 }} 批 · {{ batch.module === '.' ? '项目根目录' : batch.module }} · {{ batch.count }} 个场景 · {{ report.reviewRequired ? batch.satisfied ? '复核通过' : '复核条件未满足' : '按自定义策略不要求复核' }}（{{ batch.opinions }} 份意见）</p></details>
  </template>
</div></template>
<style scoped>.workflow-test-summary-report { overflow-wrap: anywhere; }</style>
