<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
const props = defineProps<{ content: unknown }>()
type Opinion = { perspective: 'REQUIREMENT' | 'RISK'; verdict: 'PASS' | 'BLOCKED'; reason: string }
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const opinion = (value: unknown): value is Opinion => object(value) && ['REQUIREMENT', 'RISK'].includes(String(value.perspective)) && ['PASS', 'BLOCKED'].includes(String(value.verdict)) && typeof value.reason === 'string' && !!value.reason.trim()
const report = computed(() => {
  const value = props.content
  if (!object(value) || value.version !== 1) return null
  if (value.type === 'REVIEW' && opinion(value)) return { dual: false, passed: value.verdict === 'PASS', verified: null, opinions: [value] }
  if (value.type !== 'DUAL_REVIEW' || typeof value.passed !== 'boolean' || typeof value.verificationPassed !== 'boolean' || !Array.isArray(value.reviews) || value.reviews.length !== 2 || !value.reviews.every(opinion) || new Set(value.reviews.map(row => row.perspective)).size !== 2) return null
  const proven = value.verificationPassed && value.reviews.every(row => row.verdict === 'PASS')
  if (value.passed !== proven) return null
  return { dual: true, passed: value.passed, verified: value.verificationPassed, opinions: value.reviews }
})
</script>
<template>
  <section class="workflow-review-report">
    <p v-if="!report" class="error-text">评审报告格式无法识别，请重新读取或检查原交付记录。</p>
    <template v-else>
      <h4>{{ report.dual ? '同批验收结果' : '独立评审意见' }}</h4>
      <StatusBadge :status="report.passed ? 'SUCCEEDED' : 'FAILED'" :label="report.dual ? (report.passed ? '验收通过' : '验收未通过') : (report.passed ? '评审通过' : '评审未通过')" />
      <p v-if="report.dual">程序验证：{{ report.verified ? '通过' : '未通过' }}</p>
      <article v-for="row in report.opinions" :key="row.perspective" class="workflow-review-opinion"><strong>{{ row.perspective === 'REQUIREMENT' ? '需求评审' : '风险评审' }}</strong><span>{{ row.verdict === 'PASS' ? '通过' : '未通过' }}</span><p>{{ row.reason }}</p></article>
    </template>
  </section>
</template>
<style scoped>
.workflow-review-report { border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: 12px; }
.workflow-review-opinion { margin-top: 16px; }
.workflow-review-opinion span { margin-left: 12px; }
.workflow-review-opinion p { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
