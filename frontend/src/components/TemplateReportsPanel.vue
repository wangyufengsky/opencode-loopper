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
const downloading = ref(false)
const downloadError = ref('')
const summaries = computed(() => reports.value.filter(report => report.metadata?.reportRole === 'SUMMARY'))
const latestSummary = computed(() => summaries.value.reduce<Artifact | undefined>((latest, report) => !latest || Number(report.metadata?.repairRound ?? 0) > Number(latest.metadata?.repairRound ?? 0) ? report : latest, undefined))
const body = ref<Record<string, string>>({})
const loading = ref(false)
const error = ref('')
const current = computed(() => reports.value.find(report => report.id === selected.value))
const latestRound = computed(() => Math.max(0, ...reports.value.map(report => typeof report.metadata?.repairRound === 'number' ? report.metadata.repairRound : 0)))
const currentAccepted = computed(() => props.accepted && !!current.value && (current.value.metadata?.repairRound ?? 0) === latestRound.value)
let generation = 0
watch(() => props.taskId, () => { ++generation; selected.value = ''; body.value = {}; loading.value = false; error.value = ''; downloadError.value = ''; downloading.value = false })
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
function save(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
function download() {
  if (!current.value || body.value[current.value.id] === undefined) return
  save(new Blob([body.value[current.value.id]!], { type: 'text/markdown;charset=utf-8' }), current.value.title.split('/').pop()!)
}
async function downloadBundle() {
  const report = current.value
  const taskId = props.taskId
  if (!report?.metadata?.bundleId || downloading.value) return
  downloading.value = true; downloadError.value = ''
  try {
    const blob = await api.downloadTemplateReport(taskId, report.id)
    if (props.taskId === taskId) save(blob, `${report.metadata.directoryName}.zip`)
  } catch (failure) { if (props.taskId === taskId) downloadError.value = userFacingError(failure, '报告下载失败，请重试') }
  finally { if (props.taskId === taskId) downloading.value = false }
}
function openLinkedReport(event: MouseEvent) {
  const target = event.target instanceof Element ? event.target.closest('a') : null
  const href = target?.getAttribute('href')
  const source = current.value
  if (!href || !source || /^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return
  event.preventDefault()
  let path: string
  try {
    const parts = source.title.split('/').slice(0, -1)
    const decoded = decodeURIComponent(href.split(/[?#]/)[0]!)
    if (decoded.startsWith('/') || decoded.includes('\\')) return
    for (const part of decoded.split('/')) {
      if (part === '..') { if (!parts.length) return; parts.pop() }
      else if (part && part !== '.') parts.push(part)
    }
    path = parts.join('/')
  } catch { return }
  const linked = reports.value.find(report => report.title === path && (source.metadata?.bundleId
    ? report.metadata?.bundleId === source.metadata.bundleId
    : report.metadata?.repairRound === source.metadata?.repairRound))
  if (linked) void preview(linked.id)
  else error.value = '未找到该详细报告，请刷新任务后重试'
}
</script>
<template>
  <section class="card card-pad template-reports" aria-label="模板任务报告">
    <div class="report-heading"><h2>报告</h2><span class="muted">{{ currentAccepted ? '已通过评审' : current ? '待验收版本' : accepted ? '已完成' : '待生成' }}</span></div>
    <p v-if="!reports.length" class="muted">完整证据分析完成后，报告会显示在这里。</p>
    <div v-else class="report-picker"><el-button v-if="latestSummary" @click="preview(latestSummary.id)">查看最新总结</el-button><el-select :model-value="selected" placeholder="选择报告预览" aria-label="选择报告" @change="preview"><el-option v-for="report in reports" :key="report.id" :value="report.id" :label="title(report)" /></el-select><el-button :disabled="!current || body[current.id] === undefined" @click="download">下载 Markdown</el-button><el-button v-if="current?.metadata?.bundleId" :loading="downloading" @click="downloadBundle">下载整套报告</el-button></div>
    <el-alert v-if="downloadError" :title="downloadError" type="error" :closable="false"><el-button text @click="downloadBundle">重试下载</el-button></el-alert>
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
