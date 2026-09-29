<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'SOURCE_TEST_SCOPE' || typeof value.passed !== 'boolean' || value.testsExecuted !== false
    || typeof value.message !== 'string' || !value.message.trim()) return null
  return { passed: value.passed, message: value.message }
})
</script>
<template><div class="workflow-test-scope-report">
  <template v-if="report"><StatusBadge :status="report.passed ? 'SUCCEEDED' : 'FAILED'" :label="report.passed ? '范围检查通过' : '范围检查未通过'" />
    <p>{{ report.message }}</p><p>本报告仅检查文件修改范围和已有测试保护。实际测试结果请查看后续验证节点。</p>
    <p v-if="!report.passed">可在本节点的代码交付中检查固定成果，调整任务后重试；失败成果不能直接用于后续执行。</p>
  </template><p v-else role="alert">范围报告无法读取，请重新读取本次交付物。</p>
</div></template>
<style scoped>
.workflow-test-scope-report { overflow-wrap: anywhere; }
.workflow-test-scope-report p { white-space: pre-wrap; }
</style>
