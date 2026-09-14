<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { api } from '@/api/client'
import type { DocumentReportSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
const props = defineProps<{ runId: string; count: number; completed: boolean }>()
const reports = ref<DocumentReportSummary[]>([])
const next = ref<string | null>(null)
const loading = ref(false)
const downloading = ref(false)
const error = ref('')
const current = ref<DocumentReportSummary & { content: string }>()
let generation = 0
let previewGeneration = 0
async function load(append = false) {
  const token = generation; loading.value = true
  try {
    const page = await api.documentReports(props.runId, append ? next.value ?? '' : '')
    if (token !== generation) return
    reports.value = append ? [...reports.value, ...page.items] : page.items; next.value = page.nextCursor ?? null
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '报告目录读取失败，请重试') }
  finally { if (token === generation) loading.value = false }
}
watch(() => [props.runId, props.count], () => { ++generation; void load() }, { immediate: true })
watch(() => props.runId, () => { ++previewGeneration; reports.value = []; next.value = null; current.value = undefined; downloading.value = false; error.value = '' })
async function preview(name: string) {
  const token = ++previewGeneration; loading.value = true; error.value = ''
  try {
    const report = await api.documentReportByName(props.runId, name)
    if (token === previewGeneration) current.value = report
  } catch (failure) { if (token === previewGeneration) error.value = userFacingError(failure, '报告正文读取失败，请重试') }
  finally { if (token === previewGeneration) loading.value = false }
}
function save(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob); const link = document.createElement('a')
  link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 0)
}
function downloadCurrent() {
  if (current.value) save(new Blob([current.value.content], { type: 'text/plain;charset=utf-8' }), current.value.name.split('/').pop()!)
}
async function bundle() {
  if (downloading.value) return
  const runId = props.runId; downloading.value = true; error.value = ''
  try { const blob = await api.downloadDocumentReport(runId); if (runId === props.runId) save(blob, '需求报告.zip') }
  catch (failure) { if (runId === props.runId) error.value = userFacingError(failure, '报告下载失败，请重试') }
  finally { if (runId === props.runId) downloading.value = false }
}
function linked(event: MouseEvent) {
  const href = event.target instanceof Element ? event.target.closest('a')?.getAttribute('href') : null
  if (!href || !current.value || /^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return
  event.preventDefault()
  try {
    const parts = current.value.name.split('/').slice(0, -1)
    for (const segment of decodeURIComponent(href.split(/[?#]/)[0]!).split('/')) {
      if (segment === '..') { if (!parts.length) throw new Error('invalid'); parts.pop() }
      else if (segment && segment !== '.') parts.push(segment)
    }
    void preview(parts.join('/'))
  } catch { error.value = '报告链接无效，请从目录选择报告' }
}
onBeforeUnmount(() => { ++generation; ++previewGeneration })
</script>
<template>
  <section class="card card-pad report-panel" aria-label="需求报告">
    <header><h2>需求报告 · {{ count }}</h2><el-button v-if="completed" :loading="downloading" @click="bundle">下载整包</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button text @click="load()">重新读取</el-button></el-alert>
    <p v-if="!reports.length" class="muted">{{ loading ? '正在读取报告目录…' : '尚未生成报告，已完成的需求与证据会持续保留' }}</p>
    <div v-if="reports.length" class="report-list"><el-button v-for="report in reports" :key="report.id" text @click="preview(report.name)">{{ report.name === 'summary.md' ? '总体报告' : report.name === 'matrix.json' ? '需求矩阵数据' : report.name }}</el-button></div>
    <el-button v-if="next" :loading="loading" @click="load(true)">加载更多报告</el-button>
    <div v-if="current" class="report-preview" @click="linked">
      <header><strong>{{ current.name }}</strong><el-button text @click="downloadCurrent">下载当前文件</el-button></header>
      <MarkdownDocument v-if="current.name.endsWith('.md')" :content="current.content" /><pre v-else>{{ current.content }}</pre>
    </div>
  </section>
</template>
<style scoped>
.report-panel { display: grid; gap: 16px; min-width: 0; }header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }h2 { margin: 0; font-size: 18px; }.report-list { display: flex; gap: 8px; flex-wrap: wrap; max-height: 250px; overflow: auto; }.report-preview { overflow-wrap: anywhere; min-width: 0; }pre { max-height: 600px; overflow: auto; white-space: pre-wrap; }
</style>
