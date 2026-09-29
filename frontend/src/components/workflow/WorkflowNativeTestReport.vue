<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
const frameworks: Record<string, string> = { junit: 'JUnit', testng: 'TestNG', pytest: 'pytest', jest: 'Jest', vitest: 'Vitest' }
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  const c = v?.counts as Record<string, unknown> | undefined
  if (!v || ![1, 2].includes(Number(v.version)) || typeof v.version !== 'number' || v.type !== 'SOURCE_TEST_RUN' || typeof v.valid !== 'boolean' || typeof v.passed !== 'boolean'
    || typeof v.moduleRoot !== 'string' || typeof v.framework !== 'string' || !frameworks[v.framework] || typeof v.message !== 'string' || !c
    || !['total', 'passed', 'failed', 'skipped'].every(k => Number.isInteger(c[k]) && Number(c[k]) >= 0)
    || Number(c.total) !== Number(c.passed) + Number(c.failed) + Number(c.skipped) || !Array.isArray(v.command) || !v.command.every(a => typeof a === 'string')
    || !Array.isArray(v.files) || v.files.some(f => !f || typeof f.path !== 'string') || !Number.isInteger(v.fileCount) || Number(v.fileCount) < v.files.length
    || v.scenarioCoverageVerified !== false || v.exitCode !== null && !Number.isInteger(v.exitCode)
    || v.inputUnchanged !== undefined && v.inputUnchanged !== null && typeof v.inputUnchanged !== 'boolean') return null
  let batchCount = 0
  if (v.version === 2) {
    const batches = v.batches as Record<string, unknown>[] | undefined
    const writers = v.writerLineage
    if (!Array.isArray(writers) || !writers.length || writers.length > 256 || writers.some(w => typeof w !== 'string' || !w)
      || new Set(writers).size !== writers.length || writers[0] !== v.producerAttempt
      || !Array.isArray(batches) || !batches.length || batches.length > 61 || batches.some(b => !b
        || typeof b.inputName !== 'string' || !/^design(?:_[a-zA-Z0-9_]+)?$/.test(b.inputName)
        || typeof b.designAttempt !== 'string' || !b.designAttempt || typeof b.designSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(b.designSha256)
        || !writers.includes(b.writerAttempt) || !Number.isInteger(b.scenarioCount) || Number(b.scenarioCount) < 1 || Number(b.scenarioCount) > 64)
      || new Set(batches.map(b => b.inputName)).size !== batches.length || !batches.some(b => b.inputName === 'design')
      || new Set(batches.map(b => b.designAttempt)).size !== batches.length) return null
    batchCount = batches.length
  }
  const executed = Number(c.passed) + Number(c.failed)
  const valid = v.valid && executed > 0 && v.inputUnchanged !== false
  return { module: v.moduleRoot, framework: frameworks[v.framework], command: v.command, message: v.message,
    batchCount, inputUnchanged: v.inputUnchanged, valid, passed: valid && v.passed && Number(c.failed) === 0 && v.exitCode === 0, executed, succeeded: Number(c.passed), failed: Number(c.failed), skipped: Number(c.skipped), files: v.files as { path: string }[], fileCount: Number(v.fileCount) }
})
</script>
<template><div class="workflow-native-test-report">
  <p v-if="!report" role="alert">原生测试报告无法读取，请重新加载本次交付物。</p>
  <template v-else>
    <StatusBadge :status="report.passed ? 'PASS' : 'FAILED'" :label="!report.valid ? '测试证据不完整' : report.passed ? '原生测试通过' : '原生测试未通过'" />
    <p>测试模块：{{ report.module === '.' ? '项目根目录' : report.module }} · {{ report.framework }}</p>
    <p v-if="report.batchCount">本次在同一份固定代码上回归 {{ report.batchCount }} 批场景，各批代码继承关系已核对。</p>
    <p v-if="report.inputUnchanged === true">已核对固定源码、测试和配置。</p>
    <p v-if="report.valid">实际执行 {{ report.executed }} 项：通过 {{ report.succeeded }}，失败 {{ report.failed }}，另有 {{ report.skipped }} 项跳过。</p>
    <p v-else>未取得完整的测试执行证据，不能认定测试通过。</p>
    <p>{{ report.message }}</p>
    <p>测试数量不表示每个设计场景都已覆盖；场景覆盖与独立评审由对应节点确认。</p>
    <details><summary>本次命令与报告文件</summary><pre class="workflow-command-output">{{ JSON.stringify(report.command, null, 2) }}</pre>
      <p v-for="file in report.files" :key="file.path">{{ file.path }}</p><p v-if="report.fileCount > report.files.length">另有 {{ report.fileCount - report.files.length }} 份报告，完整正文保存在执行记录中。</p>
    </details>
  </template>
</div></template>
<style scoped>.workflow-native-test-report { overflow-wrap: anywhere; }</style>
