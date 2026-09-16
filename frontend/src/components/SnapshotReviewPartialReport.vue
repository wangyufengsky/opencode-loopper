<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElAlert } from 'element-plus'
import { api } from '@/api/client'
import type { SnapshotReviewPartialReport } from '@/types/domain'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ taskId: string }>()
const report = ref<SnapshotReviewPartialReport>()
const loading = ref(false)
const error = ref('')
let generation = 0
watch(() => props.taskId, () => { generation++; report.value = undefined; error.value = ''; loading.value = false })
async function load() {
  const request = ++generation
  loading.value = true; error.value = ''
  try {
    const value = await api.snapshotReviewPartialReport(props.taskId)
    if (request === generation) report.value = value
  } catch (failure) {
    if (request === generation) error.value = userFacingError(failure, '阶段报告读取失败，请稍后重试')
  } finally { if (request === generation) loading.value = false }
}
function download() {
  if (!report.value) return
  const url = URL.createObjectURL(new Blob([report.value.content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '代码审查阶段报告.md'; link.click()
  URL.revokeObjectURL(url)
}
</script>
<template>
  <section class="card card-pad">
    <h2 class="card-title">阶段报告</h2>
    <p class="muted">查看已完成的分析与证据，任务取消后仍可读取。候选问题、复核结果和未完成范围分别标注。</p>
    <el-button :loading="loading" @click="load">{{ report ? '刷新阶段报告' : '查看阶段报告' }}</el-button>
    <el-button v-if="report" @click="download">下载阶段报告</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p>已分析 {{ report.analyzedUnits }} · 未完成 {{ report.pendingUnits }} · 排除 {{ report.excludedUnits }}</p>
      <details open><summary>阶段报告内容</summary><MarkdownDocument :content="report.content" /></details>
    </template>
  </section>
</template>
