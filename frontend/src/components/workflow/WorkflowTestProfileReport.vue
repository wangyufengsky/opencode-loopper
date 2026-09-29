<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
type Module = { root: string; framework: string; sourcePaths: string[]; testRoots: string[]; fixtureRoots: string[]; command: string[] }
const frameworks: Record<string, string> = { junit: 'JUnit', testng: 'TestNG', jest: 'Jest', vitest: 'Vitest', pytest: 'pytest' }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string' && item.length > 0)
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'SOURCE_TEST_PROFILE') return null
  if (value.complete === false) return { kind: 'failed' as const, message: typeof value.message === 'string' ? value.message : workflowReasonLabel(typeof value.code === 'string' ? value.code : null) }
  if (value.complete === true) {
    if (!Number.isSafeInteger(value.moduleCount) || Number(value.moduleCount) < 1 || !Number.isSafeInteger(value.sourceCount) || Number(value.sourceCount) < 1) return null
    return { kind: 'summary' as const, moduleCount: Number(value.moduleCount), sourceCount: Number(value.sourceCount) }
  }
  const profile = value.profile as { manifestSha256?: unknown; modules?: unknown } | null
  if (!profile || typeof profile.manifestSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(profile.manifestSha256) || !Array.isArray(profile.modules) || !profile.modules.length || profile.modules.length > 1024) return null
  const modules: Module[] = [], paths = new Set<string>()
  for (const module of profile.modules) {
    if (!module || typeof module.root !== 'string' || !module.root || typeof module.framework !== 'string' || !Object.prototype.hasOwnProperty.call(frameworks, module.framework)
      || !strings(module.sourcePaths) || !module.sourcePaths.length || !strings(module.testRoots) || !module.testRoots.length
      || !strings(module.fixtureRoots) || !strings(module.command) || !module.command.length) return null
    for (const path of module.sourcePaths) { if (paths.has(path)) return null; paths.add(path) }
    modules.push(module)
  }
  return { kind: 'profile' as const, modules }
})
</script>
<template><div class="workflow-test-profile-report">
  <template v-if="report?.kind === 'profile'"><p>以下配置来自固定源码资料，测试尚未执行。</p>
    <details v-for="module in report.modules" :key="module.root + module.framework" open>
      <summary>{{ module.root === '.' ? '项目根目录' : module.root }} · {{ frameworks[module.framework] }}</summary>
      <dl><dt>测试源码目录</dt><dd><code v-for="path in module.testRoots" :key="path">{{ path }}</code></dd>
        <template v-if="module.fixtureRoots.length"><dt>测试夹具目录</dt><dd><code v-for="path in module.fixtureRoots" :key="path">{{ path }}</code></dd></template>
        <dt>原生测试命令</dt><dd class="workflow-test-command"><code v-for="(argument, index) in module.command" :key="index">{{ argument }}</code></dd>
      </dl><details><summary>{{ module.sourcePaths.length }} 个目标源码文件</summary><ul><li v-for="path in module.sourcePaths" :key="path">{{ path }}</li></ul></details>
    </details>
  </template>
  <template v-else-if="report?.kind === 'summary'"><StatusBadge status="SUCCEEDED" label="配置已识别" /><p>{{ report.moduleCount }} 个模块，{{ report.sourceCount }} 个目标源码文件。</p></template>
  <template v-else-if="report?.kind === 'failed'"><StatusBadge status="FAILED" label="配置未确定" /><p>{{ report.message }}</p></template>
  <p v-else role="alert">测试配置无法读取，请重新读取本次交付物。</p>
</div></template>
<style scoped>
.workflow-test-profile-report { overflow-wrap: anywhere; }
.workflow-test-profile-report details { margin-block: var(--space-3); }
.workflow-test-profile-report dd { margin: var(--space-1) 0 var(--space-3); }
.workflow-test-profile-report dd code { display: block; white-space: pre-wrap; }
.workflow-test-profile-report .workflow-test-command { display: flex; flex-wrap: wrap; gap: var(--space-2); }
</style>
