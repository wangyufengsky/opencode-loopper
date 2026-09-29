<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
const props = defineProps<{ content: unknown }>()
const checks = computed(() => {
  const value = props.content as { version?: number; checks?: unknown }
  if (!value || value.version !== 1 || !Array.isArray(value.checks) || !value.checks.length) return null
  return value.checks.map((row: unknown) => {
    const item = row as Record<string, unknown> | null
    return { title: typeof item?.title === 'string' ? item.title : '交付物读取', path: typeof item?.path === 'string' ? item.path : '',
      state: item?.state === 'PASS' ? 'PASS' : item?.state === 'FAIL' ? 'FAIL' : 'ERROR', detail: typeof item?.detail === 'string' ? item.detail : '' }
  })
})
</script>
<template>
  <div>
    <p v-if="!checks" role="alert">检查报告格式无法读取，请重新读取本次交付物。</p>
    <article v-for="(check, index) in checks" :key="index" class="workflow-binding">
      <div class="workflow-inline">
        <strong>{{ check.title }}</strong>
        <StatusBadge :status="check.state === 'ERROR' ? 'FAILED' : check.state" :label="check.state === 'PASS' ? '通过' : check.state === 'FAIL' ? '未通过' : '无法检查'" />
      </div>
      <p v-if="check.path">{{ check.path }}</p>
      <p v-if="check.detail">{{ check.detail }}</p>
    </article>
  </div>
</template>
