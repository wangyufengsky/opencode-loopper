<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { TaskDecision } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string }>()
const emit = defineEmits<{ reload: []; openTask: [taskId: string] }>()
const decision = ref<TaskDecision>()
const loading = ref(false)
const acting = ref(false)
const loadError = ref('')
const actionError = ref('')
const error = computed(() => actionError.value || loadError.value)
const selectedStageId = ref('')
const supplementalRequirement = ref('')
const success = computed(() => decision.value?.cycle?.result === 'SUCCEEDED')
const action = (name: TaskDecision['availableActions'][number]) => decision.value?.availableActions.includes(name) === true
let scopeGeneration = 0
let loadGeneration = 0
let disposed = false

async function load() {
  const taskId = props.taskId
  const scope = scopeGeneration
  const request = ++loadGeneration
  const current = () => !disposed && scope === scopeGeneration && taskId === props.taskId && request === loadGeneration
  loading.value = true
  loadError.value = ''
  try {
    const next = await api.getTaskDecision(taskId)
    if (!current()) return
    decision.value = next
    if (!next.stages.some(stage => stage.id === selectedStageId.value)) {
      const preferred = next.stages.find(stage => stage.state === 'FAILED') ?? next.stages[0]
      selectedStageId.value = preferred?.id ?? ''
    }
  } catch (failure) {
    if (current()) loadError.value = userFacingError(failure, '处置状态加载失败')
  } finally {
    if (current()) loading.value = false
  }
}

function captureDecision() {
  const snapshot = decision.value
  if (disposed || acting.value || loading.value || !snapshot?.cycle || snapshot.taskId !== props.taskId) return
  const taskId = props.taskId
  const generation = scopeGeneration
  return {
    taskId,
    versions: { expectedTaskVersion: snapshot.taskVersion, expectedCycleVersion: snapshot.cycle.version },
    current: () => !disposed && props.taskId === taskId && generation === scopeGeneration,
  }
}
type DecisionScope = NonNullable<ReturnType<typeof captureDecision>>

async function runConfirmed<T>(scope: DecisionScope, message: string, title: string,
  options: { confirmButtonText: string; cancelButtonText: string; type?: 'warning' },
  operation: () => Promise<T>, onSuccess: (result: T) => void, failureMessage: string, refreshResult = true) {
  acting.value = true
  actionError.value = ''
  try {
    try { await ElMessageBox.confirm(message, title, options) } catch { return }
    if (!scope.current()) return
    try {
      const result = await operation()
      if (!scope.current()) return
      onSuccess(result)
      if (refreshResult && scope.current()) await load()
    } catch (failure) {
      if (!scope.current()) return
      actionError.value = userFacingError(failure, failureMessage)
      await load()
    }
  } finally {
    if (scope.current()) acting.value = false
  }
}

function resultReceived(result: TaskDecision, message: string) {
  if (result.taskState === 'STOPPING') ElMessage.info('取消请求已保存，正在等待远端写入者停止确认')
  else ElMessage.success(message)
  emit('reload')
}

async function continueCurrent() {
  const scope = captureDecision()
  if (!scope) return
  const succeeded = success.value
  const input = { ...scope.versions,
    stageId: succeeded ? selectedStageId.value : undefined,
    supplementalRequirement: succeeded ? supplementalRequirement.value.trim() : undefined }
  if (succeeded && (!input.stageId || !input.supplementalRequirement)) {
    actionError.value = '成功后继续优化时，请选择起始阶段并填写补充要求。'
    return
  }
  await runConfirmed(scope, succeeded
    ? '选中阶段及其后续阶段会重新执行；历史轮次、验证和审计证据保持不变，新轮次重新计算预算。'
    : '将从失败或中断阶段创建新的尝试和会话；已完成阶段保持成功。',
  '继续当前任务？', { type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '暂不继续' },
  () => api.continueTaskDecision(scope.taskId, input), result => resultReceived(result, '已创建新的执行轮次'), '处置操作失败')
}

async function derive(mode: 'INHERIT_CHANGES' | 'REWORK_ALL_STAGES') {
  const scope = captureDecision()
  if (!scope) return
  const input = { ...scope.versions, mode }
  const inherit = mode === 'INHERIT_CHANGES'
  await runConfirmed(scope, inherit
    ? '新任务从父任务原始基线创建分支，并把冻结的当前修改作为未提交内容还原。父任务会标记为已接续。'
    : '新任务从父任务原始基线重新执行，不继承半成品。父任务会标记为已接续。',
  inherit ? '新任务继承当前修改？' : '新任务全部重做？',
  { type: 'warning', confirmButtonText: inherit ? '创建接续任务' : '创建重做任务', cancelButtonText: '取消' },
  () => api.deriveTaskDecision(scope.taskId, input), child => emit('openTask', child.taskId), '派生任务失败', false)
}

async function audit() {
  const scope = captureDecision()
  if (!scope) return
  await runConfirmed(scope, '将创建只读审计任务，只执行确定性验证。',
    '直接审计当前代码？', { confirmButtonText: '创建审计任务', cancelButtonText: '取消' },
    () => api.auditTaskDecision(scope.taskId, scope.versions), child => emit('openTask', child.taskId), '创建审计任务失败', false)
}

async function accept() {
  const scope = captureDecision()
  if (!scope) return
  await runConfirmed(scope, '该轮没有文件变更。确认后任务进入“已确认完成”，执行历史保持可审计。',
    '接受无变更结果？', { confirmButtonText: '接受结果', cancelButtonText: '取消' },
    () => api.acceptTaskDecision(scope.taskId, scope.versions), result => resultReceived(result, '任务结果已确认'), '处置操作失败')
}

async function cancel() {
  const scope = captureDecision()
  if (!scope) return
  await runConfirmed(scope, '取消请求会先安全停止仍存活的写入者，再进入终态；冻结点、执行历史和审计证据仍会保留。',
    '取消任务？', { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '保留任务' },
    () => api.cancelTaskDecision(scope.taskId, scope.versions), result => resultReceived(result, '任务已取消'), '处置操作失败')
}

watch(() => props.taskId, () => {
  scopeGeneration += 1
  decision.value = undefined
  selectedStageId.value = ''
  supplementalRequirement.value = ''
  actionError.value = ''
  acting.value = false
  void load()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; scopeGeneration += 1; loadGeneration += 1 })

</script>

<template>
  <section class="decision-panel card card-pad" aria-labelledby="task-decision-heading">
    <div class="decision-header">
      <div><p class="eyebrow">用户确认</p><h2 id="task-decision-heading" class="card-title">执行结束，等待你的确认</h2></div>
      <span v-if="decision?.cycle" :class="['result-pill', success ? 'success' : 'danger']">第 {{ decision.cycle.ordinal }} 轮 · {{ success ? '执行成功' : '执行失败' }}</span>
    </div>
    <div v-if="loading" class="muted">正在读取冻结点与可用动作…</div>
    <template v-else-if="decision">
      <div :class="['checkpoint', decision.checkpoint?.state === 'READY' ? 'ready' : 'blocked']">
        <Icon :icon="decision.checkpoint?.state === 'READY' ? 'lucide:lock-keyhole' : 'lucide:shield-alert'" />
        <span v-if="decision.checkpoint?.state === 'READY'">冻结点已验证 · {{ decision.checkpoint.changedFileCount < 0 ? '变更数量未确认' : `${decision.checkpoint.changedFileCount} 个变更文件` }}</span>
        <span v-else>{{ decision.checkpoint?.blockerMessage || '冻结点尚未安全就绪，继续与派生操作已禁用。' }}</span>
      </div>
      <div v-if="success && action('CONTINUE_CURRENT_TASK')" class="continue-inputs">
        <el-select v-model="selectedStageId" placeholder="从哪个阶段继续优化">
          <el-option v-for="stage in decision.stages" :key="stage.id" :label="`阶段 ${stage.ordinal + 1} · ${stage.objective}`" :value="stage.id" />
        </el-select>
        <el-input v-model="supplementalRequirement" type="textarea" :rows="3" maxlength="12000" show-word-limit placeholder="说明还要继续修改什么；这会进入新执行轮次的实现提示。" />
      </div>
      <div class="decision-actions">
        <el-button v-if="action('CONTINUE_CURRENT_TASK')" type="primary" :loading="acting" @click="continueCurrent"><Icon icon="lucide:play" />继续当前任务</el-button>
        <el-button v-if="action('DERIVE_INHERIT_CHANGES')" :loading="acting" @click="derive('INHERIT_CHANGES')"><Icon icon="lucide:git-branch-plus" />新任务继承修改</el-button>
        <el-button v-if="action('DERIVE_REWORK_ALL')" :loading="acting" @click="derive('REWORK_ALL_STAGES')"><Icon icon="lucide:refresh-ccw" />新任务全部重做</el-button>
        <el-button v-if="action('READ_ONLY_AUDIT')" :loading="acting" @click="audit"><Icon icon="lucide:scan-eye" />直接审计</el-button>
        <el-button v-if="action('ACCEPT_RESULT')" type="success" plain :loading="acting" @click="accept"><Icon icon="lucide:check-check" />接受结果</el-button>
        <el-button v-if="action('CANCEL')" type="danger" plain :loading="acting" @click="cancel"><Icon icon="lucide:x" />取消任务</el-button>
      </div>
    </template>
    <p v-if="error" class="decision-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.decision-panel { margin-top: 16px; border-color: rgb(var(--shade-warning-02-rgb) / 38%); background: var(--appearance-task-decision-panel-decision-panel-background); }
.decision-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.result-pill { padding: 7px 10px; border: 1px solid currentColor; border-radius: 999px; font: 650 10px/1 var(--font-code); }.result-pill.success { color: var(--color-success); }.result-pill.danger { color: var(--color-danger); }
.decision-copy { margin: 10px 0 14px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.65; }.checkpoint { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid; border-radius: calc(var(--radius-control) + 2px); font-size: 11px; }.checkpoint.ready { border-color: rgb(var(--shade-success-01-rgb) / 35%); color: var(--shade-success-04); background: rgb(var(--shade-success-01-rgb) / 7%); }.checkpoint.blocked { border-color: rgb(var(--shade-border-01-rgb) / 35%); color: var(--shade-danger-02); background: rgb(var(--shade-danger-01-rgb) / 7%); }
.continue-inputs { display: grid; grid-template-columns: minmax(230px, .7fr) minmax(300px, 1.3fr); gap: 10px; margin-top: 14px; }.decision-actions { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 14px; }.decision-error { margin: 12px 0 0; color: var(--color-danger); font-size: 12px; }
@media (max-width: 780px) { .decision-header { flex-direction: column; }.continue-inputs { grid-template-columns: 1fr; }.decision-actions :deep(.el-button) { margin-left: 0; } }
</style>
