<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { SourceTemplateOverview, SourceTemplateBatch, SourceTemplateCommand } from '@/types/domain'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SourceCoveragePanel from '@/components/SourceCoveragePanel.vue'
import SourceArtifactsPanel from '@/components/SourceArtifactsPanel.vue'
import { sourceTemplateStateLabel, sourceCoverageLabel, sourceBatchStateLabel, sourceBatchFailureLabel, userFacingError } from '@/utils/displayLabels'
const route = useRoute(), router = useRouter()
const run = ref<SourceTemplateOverview>(), batches = ref<SourceTemplateBatch[]>([])
const next = ref<string | null>(), selected = ref<string[]>([])
const loading = ref(false), acting = ref(false), batchLoading = ref(false), disconnected = ref(false)
const error = ref('')
const terminal = computed(() => !!run.value && ['COMPLETED', 'CANCELLED'].includes(run.value.state))
const coverageRevision = computed(() => JSON.stringify(run.value?.coverage ?? []))
let generation = 0, requestSequence = 0, batchSequence = 0
let stream: EventSource | undefined
let fallbackTimer: ReturnType<typeof setTimeout> | undefined, refreshTimer: ReturnType<typeof setTimeout> | undefined
let pending: { action: Action; input: SourceTemplateCommand } | undefined
let batchRevision = ''
type Action = 'start' | 'cancel' | 'resume' | 'retry' | 'archive' | 'unarchive'
function toggleBatch(id: string, value: unknown) {
  selected.value = value === true ? [...new Set([...selected.value, id])] : selected.value.filter(item => item !== id)
}
async function refresh() {
  const token = generation, sequence = ++requestSequence; loading.value = true
  try {
    const current = await api.sourceTemplate(String(route.params.id))
    if (token !== generation || sequence !== requestSequence) return
    run.value = current; error.value = ''
    if (terminal.value) { stream?.close(); stream = undefined; clearTimeout(fallbackTimer) }
    const revision = JSON.stringify([current.version, current.progress, current.canResume])
    if (revision !== batchRevision && await loadBatches() && token === generation) batchRevision = revision
  } catch (failure) { if (token === generation && sequence === requestSequence) error.value = userFacingError(failure, '状态读取失败，请刷新后重试') }
  finally { if (token === generation && sequence === requestSequence) loading.value = false }
}
async function loadBatches(append = false) {
  if (!run.value || run.value.templateId !== 'DETAILED_DESIGN_WRITING') return
  const token = generation, sequence = ++batchSequence; batchLoading.value = true
  try {
    const page = await api.sourceBatches(run.value.id, append ? next.value ?? '' : '')
    if (token !== generation || sequence !== batchSequence) return
    batches.value = append ? [...batches.value, ...page.items] : page.items; next.value = page.nextCursor
    selected.value = selected.value.filter(id => batches.value.some(b => b.id === id && b.retryable))
    return true
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '批次读取失败，请重试') }
  finally { if (token === generation && sequence === batchSequence) batchLoading.value = false }
}
function invalidate() {
  if (refreshTimer) return
  refreshTimer = setTimeout(() => { refreshTimer = undefined; void refresh() }, 180)
}
function connect(token: number) {
  if (terminal.value || token !== generation || stream) return
  if (typeof EventSource !== 'undefined') {
    stream = api.sourceEvents(String(route.params.id))
    stream.addEventListener('progress', () => { disconnected.value = false; invalidate() })
    stream.onopen = () => { disconnected.value = false; invalidate() }
    stream.onerror = () => { disconnected.value = true; invalidate() }
  }
  function fallback() {
    fallbackTimer = setTimeout(async () => {
      if (token !== generation || terminal.value) return
      await refresh(); if (token === generation && !terminal.value) fallback()
    }, 10_000)
  }
  fallback()
}
watch(() => route.params.id, async () => {
  const token = ++generation; ++requestSequence; ++batchSequence
  stream?.close(); stream = undefined; clearTimeout(fallbackTimer); clearTimeout(refreshTimer); refreshTimer = undefined
  run.value = undefined; batches.value = []; next.value = null; selected.value = []; error.value = ''; pending = undefined; batchRevision = ''
  loading.value = false; acting.value = false; batchLoading.value = false
  await refresh(); connect(token)
}, { immediate: true })
async function command(action: Action) {
  const current = run.value, token = generation
  if (!current || acting.value) return
  if (action === 'cancel') {
    try { await ElMessageBox.confirm('取消本次源码模板任务？已完成结果和证据会保留，活动执行确认停止后结束。', '取消任务',
      { confirmButtonText: '确认取消', cancelButtonText: '继续执行', type: 'warning' }) } catch { return }
    if (token !== generation) return
  }
  const modelIds = action === 'retry' ? [...selected.value].sort() : undefined
  if (!pending || pending.action !== action || pending.input.expectedVersion !== current.version || JSON.stringify(pending.input.modelIds) !== JSON.stringify(modelIds))
    pending = { action, input: { requestKey: crypto.randomUUID(), expectedVersion: current.version, modelIds } }
  acting.value = true; error.value = ''; ++requestSequence; loading.value = false
  try {
    const updated = await api.sourceTemplateCommand(current.id, action, pending.input)
    if (token !== generation) return
    run.value = updated; pending = undefined; selected.value = []; await loadBatches(); connect(token)
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '操作未确认，请读取状态后使用原操作重试') }
  finally { if (token === generation) acting.value = false }
}
onBeforeUnmount(() => { ++generation; stream?.close(); clearTimeout(fallbackTimer); clearTimeout(refreshTimer) })
</script>
<template>
  <PageHeader eyebrow="模板任务" :title="run?.title ?? '源码模板任务'">
    <template #actions><el-button plain @click="router.push({ path: '/tasks', query: { type: 'template' } })">历史任务</el-button><el-button :loading="loading" @click="refresh">刷新</el-button></template>
  </PageHeader>
  <main id="main-content" class="content source-task" tabindex="-1">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!run && loading" class="muted">正在读取任务…</p>
    <template v-if="run">
      <section class="card card-pad progress-panel" aria-label="执行进度">
        <header><h2>{{ sourceTemplateStateLabel(run.state) }}</h2><div class="actions">
          <el-button v-if="run.state === 'PENDING_START' && !run.archived" type="primary" :loading="acting" @click="command('start')">开始执行</el-button>
          <el-button v-if="run.canResume" type="primary" :loading="acting" @click="command('resume')">从冻结输入恢复</el-button>
          <el-button v-if="!terminal" :loading="acting" @click="command('cancel')">{{ run.state === 'STOPPING' ? '继续确认取消' : '取消任务' }}</el-button>
          <el-button v-if="run.canArchive || run.archived" :loading="acting" @click="command(run.archived ? 'unarchive' : 'archive')">{{ run.archived ? '恢复归档' : '归档' }}</el-button>
        </div></header>
        <p v-if="run.state === 'PENDING_START'" class="muted">尚未调用模型或创建执行任务。开始后冻结源码，关联开发任务通过正式开始申请队列与目录租约。</p>
        <el-alert v-if="run.waitingMessage && ['WAITING_INPUT', 'STOPPING'].includes(run.state)" :title="run.waitingMessage" type="warning" :closable="false" />
        <p v-if="disconnected && !terminal" class="muted">实时连接中断，正在通过状态接口刷新。</p>
        <p>源码：{{ run.sourcePath }}</p>
        <p v-if="run.snapshot">已冻结 {{ run.snapshot.targetCount }} 个处理文件<span v-if="!run.snapshot.ready">，正文尚在保存</span></p>
        <div class="facts"><span v-for="item in run.coverage" :key="item.status">{{ sourceCoverageLabel(item.status) }} {{ item.count }}</span></div>
        <div class="facts"><span v-for="item in run.progress" :key="item.candidateKind + item.state">{{ item.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '复核' : '编写' }} · {{ sourceBatchStateLabel(item.state) }} {{ item.count }} 批</span></div>
        <p v-if="run.testProfile">测试目录：{{ run.testProfile.modules.flatMap(module => module.testRoots).join('、') }}</p>
        <p v-if="run.templateId === 'DETAILED_DESIGN_WRITING'">文档位置：{{ run.documentPath || '任务目录' }} 下的本次独立子目录</p>
        <div v-if="run.taskId" class="actions"><StatusBadge v-if="run.taskState" :status="run.taskState" /><RouterLink :to="`/tasks/${run.taskId}`">打开测试执行与验收</RouterLink></div>
        <RouterLink v-else-if="run.designerId" :to="{ path: '/designer', query: { sessionId: run.designerId } }">查看测试设计与待处理问题</RouterLink>
        <el-alert v-if="run.taskState === 'AWAITING_DECISION'" title="测试验收已结束，关联执行结果仍待处置，请打开执行详情处理后再归档。" type="info" :closable="false" />
        <details v-if="run.snapshot?.ready"><summary>冻结源码依据</summary><code>{{ run.snapshot.manifestSha256 }}</code></details>
      </section>
      <section v-if="batches.length" class="card card-pad" aria-label="编写与复核批次">
        <header><h2>编写与复核批次</h2><el-button v-if="run.canResume" :disabled="!selected.length" :loading="acting" @click="command('retry')">重试所选失败批次</el-button></header>
        <div class="batch-list"><article v-for="batch in batches" :key="batch.id">
          <el-checkbox v-if="run.canResume && batch.retryable" :model-value="selected.includes(batch.id)" @update:model-value="toggleBatch(batch.id, $event)" :label="`选择第 ${batch.ordinal + 1} 批${batch.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '复核' : '编写'}`" />
          <p>{{ batch.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '独立复核' : '详细设计编写' }} · 第 {{ batch.ordinal + 1 }} 批 · 第 {{ batch.attempt + 1 }} 次 · {{ sourceBatchStateLabel(batch.state) }}</p>
          <p v-if="batch.errorCode && ['FAILED', 'STOPPED'].includes(batch.state)" class="muted">{{ sourceBatchFailureLabel(batch.errorCode) }}</p>
        </article></div><el-button v-if="next" :loading="batchLoading" @click="loadBatches(true)">加载更多批次</el-button>
      </section>
      <SourceCoveragePanel :run-id="run.id" :revision="coverageRevision" :ready="!!run.snapshot?.ready" />
      <SourceArtifactsPanel v-if="run.templateId === 'DETAILED_DESIGN_WRITING'" :run-id="run.id" :version="run.version" :completed="run.state === 'COMPLETED'" />
    </template>
  </main>
</template>
<style scoped>
.source-task, .progress-panel { display: grid; gap: 18px; min-width: 0; }header, .actions, .facts { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }header { justify-content: space-between; }h2 { font-size: 18px; margin: 0; }p { line-height: 1.6; overflow-wrap: anywhere; margin: 8px 0; }code { overflow-wrap: anywhere; }summary { cursor: pointer; }.batch-list { max-height: 380px; overflow: auto; }.batch-list article { padding: 12px 0; border-bottom: 1px solid var(--color-border-default); }
</style>
