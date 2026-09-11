<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import { ElAlert } from 'element-plus'
import type { Artifact } from '@/types/domain'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string; artifacts: Artifact[]; accepted: boolean }>()
const reports = computed(() => props.artifacts.filter(artifact => artifact.kind === 'REPORT'))
const selected = ref('')
const body = ref<Record<string, string>>({})
const loading = ref(false)
const error = ref('')
const current = computed(() => reports.value.find(report => report.id === selected.value))
const latestRound = computed(() => Math.max(0, ...reports.value.map(report => typeof report.metadata?.repairRound === 'number' ? report.metadata.repairRound : 0)))
const currentAccepted = computed(() => props.accepted && !!current.value && (current.value.metadata?.repairRound ?? 0) === latestRound.value)
let generation = 0
watch(() => props.taskId, () => { ++generation; selected.value = ''; body.value = {}; loading.value = false; error.value = '' })
function title(artifact: Artifact) {
  const label = typeof artifact.metadata?.displayName === 'string' ? artifact.metadata.displayName : artifact.title.startsWith('contributors/') ? '个人贡献周报' : artifact.title
  const round = typeof artifact.metadata?.repairRound === 'number' ? artifact.metadata.repairRound : 0
  return `${label} · 第 ${round + 1} 版`
}
async function preview(id: string) {
  const request = ++generation
  selected.value = id
  error.value = ''
  if (body.value[id] !== undefined) { loading.value = false; return }
  loading.value = true
  try {
    const result = await api.getArtifactContent(props.taskId, id)
    if (request === generation) body.value[id] = result.content
  } catch (failure) { if (request === generation) error.value = userFacingError(failure, '报告读取失败，请重试') }
  finally { if (request === generation) loading.value = false }
}
function download() {
  if (!current.value || body.value[current.value.id] === undefined) return
  const url = URL.createObjectURL(new Blob([body.value[current.value.id]!], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = current.value.title.replace(/\//g, '-'); link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
function openLinkedReport(event: MouseEvent) {
  const target = event.target instanceof Element ? event.target.closest('a') : null
  const path = target?.getAttribute('href')
  if (!path || !current.value) return
  const linked = reports.value.find(report => report.title === path && report.metadata?.repairRound === current.value?.metadata?.repairRound)
  if (!linked) return
  event.preventDefault()
  void preview(linked.id)
}
</script>
<template>
  <section class="card card-pad template-reports" aria-label="模板任务报告">
    <div class="report-heading"><h2>报告</h2><span class="muted">{{ currentAccepted ? '该版本已通过双评审' : current ? '此版本供查看；通过双评审后完成任务' : accepted ? '任务已完成，请选择报告' : '生成并通过双评审后完成' }}</span></div>
    <p v-if="!reports.length" class="muted">完整证据分析完成后，报告会显示在这里。</p>
    <div v-else class="report-picker"><el-select :model-value="selected" placeholder="选择报告预览" aria-label="选择报告" @change="preview"><el-option v-for="report in reports" :key="report.id" :value="report.id" :label="title(report)" /></el-select><el-button :disabled="!current || body[current.id] === undefined" @click="download">下载 Markdown</el-button></div>
    <p v-if="loading" class="muted">正在读取报告…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button text @click="preview(selected)">重试</el-button></el-alert>
    <div v-if="current && body[current.id] !== undefined" @click.capture="openLinkedReport"><MarkdownDocument :content="body[current.id]!" /></div>
  </section>
</template>
<style scoped>
.template-reports { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; min-width: 0; }
.report-heading, .report-picker { display: flex; align-items: center; gap: 16px; justify-content: space-between; }
.report-heading h2 { margin: 0; font-size: 18px; }
.report-picker :deep(.el-select) { flex: 1; min-width: 0; }
@media (max-width: 700px) { .report-heading, .report-picker { flex-direction: column; align-items: stretch; } }
</style>
