<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
import { repositoryBranchLabel } from './repository'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'REPOSITORY_SOURCE' || typeof value.complete !== 'boolean' || typeof value.branchId !== 'string') return null
  const count = (name: string) => typeof value[name] === 'number' && Number.isSafeInteger(value[name]) && Number(value[name]) >= 0 ? Number(value[name]) : null
  const files = count('fileCount'), excluded = count('excludedCount')
  if (value.complete && (typeof value.commitSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value.commitSha) || typeof value.projectPrefix !== 'string' || files == null || files > 50000 || excluded == null || excluded > files)) return null
  return { complete: value.complete, branch: repositoryBranchLabel(value.branchId), commit: value.complete ? String(value.commitSha) : '', prefix: value.projectPrefix, files, excluded, code: typeof value.code === 'string' ? value.code : null }
})
</script>
<template><div class="workflow-repository-report">
  <p v-if="!report" role="alert">代码采集报告无法读取，请重新读取本次交付物。</p>
  <template v-else>
    <StatusBadge :status="report.complete ? 'SUCCEEDED' : 'FAILED'" :label="report.complete ? '分支代码已固定' : '采集未完成'" />
    <p>代码来源：{{ report.branch }}</p><p v-if="report.commit">固定提交：<code>{{ report.commit }}</code></p>
    <p v-if="report.prefix">项目目录：{{ report.prefix }}</p>
    <p v-if="report.complete">清单 {{ report.files }} 项 · 限制读取 {{ report.excluded }} 项</p>
    <p v-if="report.excluded">文件列表保留每项限制原因；受保护、符号链接和超限文件不提供正文。</p>
    <p v-if="report.code" role="alert">{{ workflowReasonLabel(report.code) }}</p>
  </template>
</div></template>
<style scoped>.workflow-repository-report code { overflow-wrap: anywhere; }</style>
