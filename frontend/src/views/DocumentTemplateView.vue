<script setup lang="ts">
import DocumentFileSummary from '@/components/DocumentFileSummary.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import TemplateBatchRecoveryPanel from '@/components/TemplateBatchRecoveryPanel.vue'
import { api } from '@/api/client'
import type { DocumentTemplateOverview } from '@/types/domain'
import PageHeader from '@/components/PageHeader.vue'
import DocumentRequirementsPanel from '@/components/DocumentRequirementsPanel.vue'
import DocumentSourcesPanel from '@/components/DocumentSourcesPanel.vue'
import DocumentReportsPanel from '@/components/DocumentReportsPanel.vue'
import DocumentSupplementForm from '@/components/DocumentSupplementForm.vue'
import { documentTemplateStateLabel, userFacingError } from '@/utils/displayLabels'
const route = useRoute(); const router = useRouter()
const run = ref<DocumentTemplateOverview>()
const loading = ref(false)
const acting = ref(false)
const error = ref('')
const disconnected = ref(false)
const terminal = computed(() => !!run.value && ['CANCELLED', 'COMPLETED'].includes(run.value.state))
let generation = 0
let stream: EventSource | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let pending: { action: 'cancel' | 'resume'; requestKey: string; expectedVersion: number } | undefined
async function refresh() {
  if (loading.value) return
  const token = generation; const id = String(route.params.id); loading.value = true
  try {
    const current = await api.documentTemplate(id)
    if (token !== generation) return
    run.value = current; error.value = ''
    if (['CANCELLED', 'COMPLETED'].includes(current.state)) { stream?.close(); stream = undefined }
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '任务状态读取失败，请重试') }
  finally { if (token === generation) loading.value = false }
}
function invalidate() {
  if (refreshTimer) return
  refreshTimer = setTimeout(() => { refreshTimer = undefined; void refresh() }, 180)
}
function fallback(token: number) {
  timer = setTimeout(async () => {
    if (token !== generation || terminal.value) return
    await refresh(); fallback(token)
  }, 10_000)
}
watch(() => route.params.id, async () => {
  const token = ++generation
  stream?.close(); clearTimeout(timer); clearTimeout(refreshTimer); refreshTimer = undefined
  run.value = undefined; loading.value = false; acting.value = false; pending = undefined; error.value = ''; disconnected.value = false
  await refresh()
  if (token !== generation || terminal.value) return
  if (typeof EventSource !== 'undefined') {
    stream = api.documentEvents(String(route.params.id))
    stream.addEventListener('progress', () => { disconnected.value = false; invalidate() })
    stream.onopen = () => { disconnected.value = false; invalidate() }
    stream.onerror = () => { disconnected.value = true; invalidate() }
  }
  fallback(token)
}, { immediate: true })
async function command(action: 'cancel' | 'resume') {
  const current = run.value
  if (!current || acting.value) return
  if (action === 'cancel') {
    try { await ElMessageBox.confirm('取消本次需求任务？已保存的需求与证据会保留，活动执行确认停止后才会结束。', '取消任务', { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '继续任务' }) }
    catch { return }
  }
  if (!pending || pending.action !== action || pending.expectedVersion !== current.version)
    pending = { action, requestKey: crypto.randomUUID(), expectedVersion: current.version }
  const token = generation; acting.value = true; error.value = ''
  try {
    const updated = await api.documentTemplateCommand(current.id, action, pending)
    if (token === generation) { run.value = updated; pending = undefined }
  } catch (failure) {
    if (token === generation) error.value = userFacingError(failure, '操作未确认，请读取最新状态后重试')
  } finally { if (token === generation) acting.value = false }
}
onBeforeUnmount(() => { ++generation; stream?.close(); clearTimeout(timer); clearTimeout(refreshTimer) })
</script>
<template>
  <PageHeader eyebrow="模板任务" :title="run?.title ?? '需求任务'">
    <template #actions><el-button plain @click="router.push({ path: '/tasks', query: { type: 'template' } })">历史任务</el-button><el-button :loading="loading" @click="refresh">刷新</el-button></template>
  </PageHeader>
  <main id="main-content" class="content document-task" tabindex="-1">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!run && loading" class="muted">正在读取任务…</p>
    <template v-if="run">
      <section class="card card-pad document-progress" aria-label="任务进度">
        <header><h2>{{ documentTemplateStateLabel(run.state) }}</h2><div class="actions"><el-button v-if="run.canResume" type="primary" :loading="acting" @click="command('resume')">从冻结输入恢复</el-button><el-button v-if="run.canCancel" :loading="acting" @click="command('cancel')">取消任务</el-button></div></header>
        <el-alert v-if="run.waitingMessage && ['WAITING_INPUT', 'STOPPING'].includes(run.state)" :title="run.waitingMessage" type="warning" :closable="false" />
        <p v-if="!run.uploadReady" class="muted">文档尚未保存完整。若上传已中断，请回到模板入口选择原文件重试。</p>
        <p v-if="disconnected && !terminal" class="muted">实时连接中断，正在通过状态接口同步进度。</p>
        <div class="facts"><span>文档 {{ run.files.length }} 份</span><span v-if="run.sourceKind === 'DOCUMENT_SOURCE'">原文版本 {{ run.sourceRevision }}</span><span v-if="run.sourceKind !== 'DOCUMENT_SOURCE' || run.requirementRevision > 0">{{ run.sourceKind === 'DOCUMENT_SOURCE' ? '评审条目' : '已复核需求' }} {{ run.progress.requirements }} 项</span><span v-if="run.templateId !== 'REQUIREMENT_DEVELOPMENT'">并发上限 {{ run.analysisConcurrency ?? 1 }}</span><span>分析尝试 {{ run.progress.validated }}/{{ run.progress.attempts }} 已完成</span><span v-if="run.progress.active">{{ run.progress.active }} 次分析处理中</span></div>
        <p v-if="run.templateId === 'REQUIREMENT_CODE_REVIEW'" class="muted">{{ run.state === 'COMPLETED' ? '静态评审已完成；需求是否满足以逐项结论为准。' : '按功能检查冻结代码及相关依赖。' }}本次未执行构建或测试。</p>
        <el-alert v-if="run.taskState === 'AWAITING_DECISION'" title="开发执行已结束，结果仍待你处置。请打开开发执行与验收，处理结果后再归档。" type="info" :closable="false" />
        <TemplateBatchRecoveryPanel v-if="run.templateVersion === '3' && run.templateId === 'REQUIREMENT_CODE_REVIEW' && ['ASSESSING', 'VERIFYING'].includes(run.state)" :document-run="run" />
        <details v-if="run.snapshotSha"><summary>评审代码版本</summary><code>{{ run.snapshotSha }}</code></details>
        <div v-if="run.taskId || run.designerId" class="actions">
          <RouterLink v-if="run.taskId" :to="`/tasks/${run.taskId}`">打开开发执行与验收</RouterLink>
          <RouterLink v-else-if="run.designerId" :to="{ path: '/designer', query: { sessionId: run.designerId } }">查看设计与待处理问题</RouterLink>
        </div>
      </section>
      <section class="card card-pad" aria-label="上传文档"><h2>文档与提取局限</h2>
        <details v-for="file in run.files" :key="file.id"><summary class="document-summary"><DocumentFileSummary :filename="file.filename" :section-count="file.sectionCount" :limitations="file.limitations.length" /></summary>
          <ul><li v-for="(limit, index) in file.limitations" :key="index">{{ limit }}</li></ul>
          <p v-if="!file.limitations.length" class="muted">解析器未报告提取局限；段落处理覆盖仍需原文复核。</p>
        </details>
      </section>
      <DocumentSourcesPanel :run="run" />
      <DocumentRequirementsPanel v-if="run.sourceKind !== 'DOCUMENT_SOURCE' || run.requirementRevision > 0" :run="run" @updated="run = $event" />
      <DocumentSupplementForm v-if="run.templateId === 'REQUIREMENT_DEVELOPMENT' && (run.state === 'WAITING_INPUT' || !run.uploadReady && (run.sourceRevision ?? run.requirementRevision) > 0)" :run="run" @updated="run = $event" />
      <DocumentReportsPanel :run-id="run.id" :count="run.progress.reports" :completed="run.state === 'COMPLETED'" />
    </template>
  </main>
</template>
<style scoped>
.document-summary { list-style: none; }.document-summary::-webkit-details-marker { display: none; }.document-summary:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 3px; border-radius: var(--radius-control); }

.document-task { display: grid; gap: 20px; }.document-progress { display: grid; gap: 16px; }header, .actions, .facts { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; }header { justify-content: space-between; }h2 { font-size: 18px; margin: 0 0 12px; }header h2 { margin: 0; }details { margin-top: 14px; line-height: 1.8; overflow-wrap: anywhere; }summary { cursor: pointer; }.facts { color: var(--color-text-secondary); }@media (max-width: 700px) { .actions { width: 100%; } }
</style>
