<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { createRecoveryCommandId } from '@/utils/recoveryCommandId'
import { api } from '@/api/client'
import type { TemplateDiagnosticFilter, TemplateRecoveryAction, TemplateSessionDiagnostic } from '@/types/domain'
import { templateDiagnosticPhaseLabel, userFacingError } from '@/utils/displayLabels'
import { templateBatchPurpose } from '@/utils/templateSessionLabels'

const props = defineProps<{ taskId: string; active: boolean }>()
const emit = defineEmits<{ select: [sessionKey: string] }>()
const filters: { value: TemplateDiagnosticFilter; label: string }[] = [
  { value: 'ATTENTION', label: '需要关注' }, { value: 'ACTIVE', label: '未完成' }, { value: 'ALL', label: '全部批次' },
]
const filter = ref<TemplateDiagnosticFilter>('ATTENTION')
const items = ref<TemplateSessionDiagnostic[]>([])
const cursor = ref<string>()
const previous = ref<(string | undefined)[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const expanded = ref('')
const detail = ref<TemplateSessionDiagnostic>()
const detailLoading = ref(false)
const detailError = ref('')
const recovering = ref('')
let scopeEpoch = 0
let epoch = 0
let detailEpoch = 0
let timer: ReturnType<typeof setTimeout> | undefined
const commands = new Map<string, string>()

function time(value?: string | null) {
  if (!value) return '暂无记录'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '时间不可用' : parsed.toLocaleString('zh-CN', { hour12: false })
}
function elapsed(since: string | null, observed: string | null) {
  if (!since || !observed) return ''
  const milliseconds = Date.parse(observed) - Date.parse(since)
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return ''
  const minutes = Math.floor(milliseconds / 60000)
  return minutes < 1 ? '不足 1 分钟' : `${minutes} 分钟`
}
function title(row: TemplateSessionDiagnostic) {
  return `阶段 ${row.stageOrdinal} · ${templateBatchPurpose(row.purpose)} · 第 ${row.ordinal} 批`
}
function stopTimer() { if (timer) clearTimeout(timer); timer = undefined }
function schedule(token: number) {
  stopTimer()
  if (!props.active || token !== epoch) return
  timer = setTimeout(() => {
    if (document.hidden) schedule(token)
    else void load()
  }, 5000)
}
async function load() {
  stopTimer()
  const token = ++epoch
  loading.value = true
  try {
    const result = await api.getTemplateSessionDiagnostics(props.taskId, filter.value, cursor.value, 50)
    if (token !== epoch) return
    items.value = result.items
    nextCursor.value = result.hasMore ? result.nextCursor : null
    error.value = ''
    if (expanded.value && !items.value.some(row => row.batchId === expanded.value)) closeDetails()
    else if (expanded.value) void loadDetails(expanded.value)
  } catch (cause) {
    if (token === epoch) error.value = userFacingError(cause, '批次诊断加载失败，请刷新重试')
  } finally {
    if (token === epoch) { loading.value = false; schedule(token) }
  }
}
function changeFilter(value: TemplateDiagnosticFilter) {
  filter.value = value
  cursor.value = undefined
  previous.value = []
  items.value = []
  closeDetails()
  void load()
}
function page(next: boolean) {
  if (next) { previous.value.push(cursor.value); cursor.value = nextCursor.value ?? undefined }
  else cursor.value = previous.value.pop()
  closeDetails()
  void load()
}
function closeDetails() {
  detailEpoch++
  expanded.value = ''
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = false
}
async function loadDetails(batchId: string) {
  const token = ++detailEpoch
  const taskId = props.taskId
  detailLoading.value = true
  try {
    const result = await api.getTemplateSessionDiagnostic(taskId, batchId)
    if (token !== detailEpoch || taskId !== props.taskId) return
    detail.value = result
    detailError.value = ''
  } catch (cause) {
    if (token === detailEpoch) detailError.value = userFacingError(cause, '诊断详情加载失败，请重试')
  } finally { if (token === detailEpoch) detailLoading.value = false }
}
function toggleDetails(row: TemplateSessionDiagnostic) {
  if (expanded.value === row.batchId) return closeDetails()
  closeDetails()
  expanded.value = row.batchId
  void loadDetails(row.batchId)
}
function summary(row: TemplateSessionDiagnostic) {
  // Explicit allowlist: never copy arbitrary server data, transcripts, or credentials.
  return JSON.stringify({ taskId: props.taskId, batchId: row.batchId, batchVersion: row.batchVersion,
    sessionKey: row.sessionKey, localSessionId: row.localSessionId, externalSessionId: row.externalSessionId,
    requestMessageId: row.requestMessageId, worktreePath: row.worktreePath,
    stageOrdinal: row.stageOrdinal, purpose: row.purpose, ordinal: row.ordinal, generation: row.generation,
    state: row.state, phase: row.phase, reason: row.reason, remoteState: row.remoteState, connected: row.connected,
    acceptedAt: row.acceptedAt, submissionRevision: row.submissionRevision, candidateAccepted: row.candidateAccepted,
    observedAt: row.observedAt, lastActivityAt: row.lastActivityAt, lastProgressAt: row.lastProgressAt,
    stopProof: row.stopProof, stopConfirmedAt: row.stopConfirmedAt, recoveryAction: row.recoveryAction,
    recoveryRequestedAt: row.recoveryRequestedAt, automaticRetries: row.automaticRetries, retryLimit: row.retryLimit,
    nextRetryAt: row.nextRetryAt, failedOperation: row.failedOperation, transportError: row.transportError,
    transportMessage: row.transportMessage, transportFailures: row.transportFailures,
    firstFailedAt: row.firstFailedAt, lastFailedAt: row.lastFailedAt, nextCheckAt: row.nextCheckAt }, null, 2)
}
async function check(row: TemplateSessionDiagnostic) {
  if (!row.canCheck || recovering.value || loading.value || error.value) return
  const taskId = props.taskId
  const scope = scopeEpoch
  recovering.value = row.batchId
  try {
    await api.checkTemplateSession(taskId, row.batchId, row.batchVersion)
    if (scope !== scopeEpoch || taskId !== props.taskId) return
    notice.value = '已请求重新检查原会话，不会因此重新提交分析请求。'
    await load()
  } catch (cause) {
    if (scope === scopeEpoch) error.value = userFacingError(cause, '检查请求尚未确认，请刷新状态后再操作')
  } finally { if (scope === scopeEpoch) recovering.value = '' }
}
async function copySummary() {
  if (!detail.value) return
  try { await navigator.clipboard.writeText(summary(detail.value)); notice.value = '诊断摘要已复制' }
  catch { detailError.value = '复制失败，请选中下方诊断摘要手动复制' }
}
async function recover(row: TemplateSessionDiagnostic, action: TemplateRecoveryAction) {
  if (recovering.value || loading.value || error.value || (action === 'FINALIZE' ? !row.canFinalize : !row.canStop)) return
  const taskId = props.taskId
  const scope = scopeEpoch
  recovering.value = row.batchId
  notice.value = ''
  try {
    if (action === 'STOP') {
      try { await ElMessageBox.confirm(`只停止${title(row)}的当前会话。停止确认后，该批次可能需要单独重试，已完成批次保持不变。`, '停止此批次', { type: 'warning', confirmButtonText: '确认停止此批次', cancelButtonText: '返回' }) }
      catch { return }
    }
    if (scope !== scopeEpoch || taskId !== props.taskId) return
    const commandKey = `${taskId}:${row.batchId}:${row.batchVersion}:${action}`
    let commandId = commands.get(commandKey)
    if (!commandId) { commandId = createRecoveryCommandId(); commands.set(commandKey, commandId) }
    const result = await api.recoverTemplateSession(taskId, row.batchId, { action, expectedVersion: row.batchVersion, commandId })
    if (scope !== scopeEpoch || taskId !== props.taskId) return
    notice.value = '恢复请求已记录，正在等待服务端核实停止并更新批次。'
    items.value = items.value.map(item => item.batchId === result.batchId ? result : item)
    await load()
  } catch (cause) {
    if (scope === scopeEpoch && taskId === props.taskId) {
      error.value = userFacingError(cause, '恢复请求未确认，请刷新状态后重试；同一请求将复用原命令标识')
      // Preserve command identity on uncertain delivery; never generate a new intent on retry.
    }
  } finally { if (scope === scopeEpoch && taskId === props.taskId) recovering.value = '' }
}
watch(() => props.taskId, () => {
  scopeEpoch++; epoch++; stopTimer(); closeDetails(); commands.clear()
  filter.value = 'ATTENTION'; cursor.value = undefined; previous.value = []
  items.value = []; notice.value = ''; error.value = ''; recovering.value = ''
  void load()
}, { immediate: true })
watch(() => props.active, () => schedule(epoch))
onBeforeUnmount(() => { scopeEpoch++; epoch++; detailEpoch++; stopTimer() })
</script>

<template>
  <section class="batch-diagnostics" aria-label="批次运行诊断">
    <header class="diagnostic-header">
      <strong>批次运行诊断</strong>
      <nav aria-label="批次诊断筛选">
        <button v-for="tab in filters" :key="tab.value" type="button" :aria-pressed="filter === tab.value" :disabled="Boolean(recovering)" @click="changeFilter(tab.value)">{{ tab.label }}</button>
      </nav>
      <button type="button" :disabled="loading || Boolean(recovering)" @click="load">{{ loading ? '检查中…' : '刷新状态' }}</button>
    </header>
    <p class="diagnostic-hint">连接正常只表示可读取会话；活动、有效进展和停止确认分别记录。</p>
    <p v-if="error" role="alert" class="diagnostic-warning">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!loading && !error && !items.length" class="diagnostic-hint">{{ filter === 'ATTENTION' ? '当前没有需要关注的批次。可切换“未完成”查看仍在执行的批次。' : '当前筛选下没有批次。' }}</p>
    <div class="diagnostic-rows" :aria-busy="loading">
      <article v-for="row in items" :key="row.batchId" class="diagnostic-row">
        <div class="diagnostic-title"><strong>{{ title(row) }}</strong><span>{{ templateDiagnosticPhaseLabel(row.phase) }}</span></div>
        <p>{{ row.reason }}</p>
        <p v-if="row.retryLimit">本轮自动重试已用 {{ row.automaticRetries ?? 0 }}/{{ row.retryLimit }} 次<span v-if="row.nextRetryAt">；下次重新分析：{{ time(row.nextRetryAt) }}</span></p>
        <p v-if="row.transportFailures">连续 {{ row.transportFailures }} 次未能完成检查；下次检查：{{ time(row.nextCheckAt) }}。查询重试不计入自动重新分析次数。</p>
        <dl class="diagnostic-times">
          <div><dt>最后检查 · {{ row.connected ? '连接正常' : '未确认连接' }}</dt><dd>{{ time(row.observedAt) }}</dd></div>
          <div><dt>最后活动</dt><dd>{{ time(row.lastActivityAt) }}</dd><dd v-if="elapsed(row.lastActivityAt, row.observedAt)" class="diagnostic-hint">截至最后检查，{{ elapsed(row.lastActivityAt, row.observedAt) }}无新活动</dd></div>
          <div><dt>最后有效进展</dt><dd>{{ time(row.lastProgressAt) }}</dd></div>
          <div v-if="row.acceptedAt"><dt>结果接受时间</dt><dd>{{ time(row.acceptedAt) }}</dd></div>
          <div v-if="row.stopConfirmedAt"><dt>停止确认时间</dt><dd>{{ time(row.stopConfirmedAt) }}</dd></div>
        </dl>
        <div class="diagnostic-actions">
          <button v-if="row.sessionKey" type="button" @click="emit('select', row.sessionKey)">查看对应会话</button>
          <button type="button" :aria-expanded="expanded === row.batchId" @click="toggleDetails(row)">{{ expanded === row.batchId ? '收起诊断' : '查看诊断详情' }}</button>
          <button v-if="row.canCheck" type="button" :disabled="loading || Boolean(recovering) || Boolean(error)" @click="check(row)">重新检查会话</button>
          <button v-if="row.canFinalize" type="button" :disabled="loading || Boolean(recovering) || Boolean(error)" @click="recover(row, 'FINALIZE')">{{ recovering === row.batchId ? '请求处理中…' : '结束会话并收尾' }}</button>
          <button v-if="row.canStop" type="button" :disabled="loading || Boolean(recovering) || Boolean(error)" @click="recover(row, 'STOP')">{{ recovering === row.batchId ? '请求处理中…' : '停止此批次' }}</button>
        </div>
        <div v-if="expanded === row.batchId" class="diagnostic-detail">
          <p v-if="detailLoading">正在读取诊断详情…</p>
          <p v-if="detailError" role="alert" class="diagnostic-warning">{{ detailError }} <button type="button" @click="loadDetails(row.batchId)">重试读取</button></p>
          <template v-if="detail && detail.batchId === row.batchId">
            <p class="diagnostic-hint">诊断摘要包含目录和会话标识，不含认证凭据或模型输出。</p>
            <button type="button" :disabled="detailLoading || Boolean(detailError)" @click="copySummary">复制诊断摘要</button>
            <pre tabindex="0">{{ summary(detail) }}</pre>
          </template>
        </div>
      </article>
    </div>
    <footer v-if="previous.length || nextCursor" class="diagnostic-actions">
      <button type="button" :disabled="loading || Boolean(recovering) || !previous.length" @click="page(false)">上一页</button>
      <span>第 {{ previous.length + 1 }} 页</span>
      <button type="button" :disabled="loading || Boolean(recovering) || !nextCursor" @click="page(true)">下一页</button>
    </footer>
  </section>
</template>

<style scoped>
.batch-diagnostics { padding: 16px 20px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-secondary); font-size: 12px; }
.diagnostic-header,.diagnostic-header nav,.diagnostic-actions,.diagnostic-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.diagnostic-header { justify-content: space-between; }.diagnostic-title { justify-content: space-between; color: var(--color-text-primary); }.diagnostic-title span { color: var(--color-accent-cyan); }
button { border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-elevated); color: var(--color-text-primary); padding: 6px 10px; font: inherit; cursor: pointer; }
button[aria-pressed="true"] { border-color: var(--color-accent-cyan); color: var(--color-accent-cyan); }button:disabled { opacity: .5; cursor: default; }button:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 2px; }
.diagnostic-hint { color: var(--color-text-muted); line-height: 1.6; }.diagnostic-warning { color: var(--color-session-warning); }
.diagnostic-rows { max-height: 580px; overflow: auto; }.diagnostic-row { margin-top: 10px; padding: 14px; border: 1px solid var(--color-border-default); border-radius: calc(var(--radius-control) + 2px); overflow-wrap: anywhere; }.diagnostic-row p { line-height: 1.6; }
.diagnostic-times { display: grid; grid-template-columns: repeat(auto-fit,minmax(180px,1fr)); gap: 12px; margin: 12px 0; }.diagnostic-times dt { color: var(--color-text-muted); }.diagnostic-times dd { margin: 5px 0 0; font-variant-numeric: tabular-nums; }
.diagnostic-detail { margin-top: 12px; border-top: 1px solid var(--color-border-default); }.diagnostic-detail pre { max-height: 300px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: 11px/1.6 var(--font-code); }.diagnostic-actions { margin-top: 10px; }
@media (max-width:640px) { .batch-diagnostics { padding: 12px; }.diagnostic-header { align-items: flex-start; }.diagnostic-times { grid-template-columns: 1fr; } }
</style>
