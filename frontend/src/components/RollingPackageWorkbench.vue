<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import type { RollingPackageDetail, RollingPackageRun, RollingPackageWorkbench, RollingPlanPackage, Task } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ task: Task }>()
const emit = defineEmits<{ refresh: [] }>()
const workbench = ref<RollingPackageWorkbench>()
const detail = ref<RollingPackageDetail>()
const selectedId = ref('')
const feedback = ref('')
const busy = ref(false)
const error = ref('')
const replanOpen = ref(false)
const planDraft = ref<RollingPlanPackage[]>([])

const selected = computed(() => workbench.value?.packages.find(item => item.id === selectedId.value))
const current = computed(() => workbench.value?.currentPackageRunId === selectedId.value)
const capabilities = computed(() => current.value ? workbench.value?.packageCapabilities : undefined)
const snapshotChanged = computed(() => {
  const wb = workbench.value
  if (!wb || props.task.version === undefined) return false
  const currentRun = wb.packages.find(item => item.id === wb.currentPackageRunId)
  return props.task.version !== wb.taskVersion
    || props.task.currentPackage?.id !== wb.currentPackageRunId
    || props.task.currentPackage?.version !== currentRun?.version
})
const policyCopy = computed(() => workbench.value?.workspacePolicy === 'PINNED_DIRECT'
  ? 'Direct 模式：从首次执行开始到任务完成或取消，登记目录会持续由本任务占用；包间会复核目录是否漂移。'
  : 'Git 模式：每包事实冻结后释放登记目录租约；下一包执行时从已验证 Checkpoint 精确恢复。')

function pretty(value?: string) {
  if (!value) return '尚未形成'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

function cancelled(cause: unknown) {
  return cause === 'cancel' || cause === 'close'
    || cause instanceof Error && ['cancel', 'close'].includes(cause.message)
}

async function reportActionFailure(cause: unknown, fallback: string, preferred?: string) {
  const message = userFacingError(cause, fallback)
  if (cause && typeof cause === 'object' && 'status' in cause && cause.status === 409) {
    emit('refresh')
    await load(preferred)
    error.value = `${message}；工作包状态已刷新。`
    return
  }
  error.value = message
}

async function load(preferred?: string) {
  error.value = ''
  try {
    workbench.value = await api.getRollingPackageWorkbench(props.task.id)
    const next = preferred || selectedId.value || props.task.currentPackage?.id || workbench.value.packages[0]?.id || ''
    selectedId.value = workbench.value.packages.some(item => item.id === next) ? next : workbench.value.packages[0]?.id || ''
    detail.value = selectedId.value ? await api.getRollingPackageDetail(props.task.id, selectedId.value) : undefined
  } catch (cause) {
    error.value = userFacingError(cause, '逐包工作台加载失败')
  }
}

async function selectPackage(run: RollingPackageRun) {
  selectedId.value = run.id
  await load(run.id)
}

async function act(action: 'approve' | 'start' | 'redesign' | 'discuss' | 'checkpoint') {
  const run = selected.value
  const wb = workbench.value
  if (!run || !wb || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const versions = { expectedTaskVersion: wb.taskVersion, expectedPackageVersion: run.version,
      expectedDiscussionRevision: run.discussionRevision, expectedDesignRevision: run.designRevision }
    if (action === 'start') await api.startRollingPackage(props.task.id, run.id, versions)
    if (action === 'redesign') await api.redesignRollingPackage(props.task.id, run.id, versions)
    if (action === 'checkpoint') await api.retryRollingPackageCheckpoint(props.task.id, run.id, versions)
    if (action === 'approve') await api.approveRollingPackageDesign(props.task.id, run.id, {
      ...versions,
    })
    if (action === 'discuss') {
      if (!feedback.value.trim()) return
      await api.discussRollingPackage(props.task.id, run.id, {
        ...versions, expectedDiscussionRevision: run.discussionRevision,
        expectedDesignRevision: run.designRevision, content: feedback.value.trim(),
      })
      feedback.value = ''
    }
    ElMessage.success(action === 'start' ? '工作包已请求执行' : action === 'approve' ? '工作包设计已确认' : '请求已提交')
    emit('refresh')
    await load(run.id)
  } catch (cause) {
    await reportActionFailure(cause, '工作包操作失败；请刷新后基于最新版本重试', run.id)
  } finally {
    busy.value = false
  }
}

async function resolveFailure(action: 'CONTINUE_CANDIDATE' | 'REDESIGN_FROM_PREVIOUS' | 'ABANDON_TASK') {
  const run = selected.value
  const wb = workbench.value
  if (!run || !wb) return
  try {
    await ElMessageBox.confirm(action === 'CONTINUE_CANDIDATE'
      ? '将从失败候选 Checkpoint 继续实现，失败变更和历史 Attempt 会保留。'
      : action === 'REDESIGN_FROM_PREVIOUS' ? '将丢弃失败候选作为恢复起点，回到上一成功事实点重新设计当前包。'
        : '将取消整个任务并保留现有文件与审计证据。', '确认失败包处置？', { type: 'warning' })
  } catch { return }
  busy.value = true
  try {
    await api.resolveRollingPackageFailure(props.task.id, run.id, {
      expectedTaskVersion: wb.taskVersion, expectedPackageVersion: run.version,
      expectedDiscussionRevision: run.discussionRevision, expectedDesignRevision: run.designRevision, action,
    })
    emit('refresh'); await load(run.id)
  } catch (cause) { error.value = userFacingError(cause, '失败包处置未完成') }
  finally { busy.value = false }
}

function openReplan() {
  if (!workbench.value) return
  planDraft.value = workbench.value.packages.filter(item => !['FACT_FROZEN', 'SUPERSEDED', 'CANCELLED'].includes(item.state))
    .map(item => ({ packageKey: item.packageKey, title: item.title, objective: item.title,
      sourcePackageRunId: item.id, sourcePackageRunIds: [item.id], dependencies: [...item.dependencies], requirementRefs: [] }))
  replanOpen.value = true
}

function movePlan(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= planDraft.value.length) return
  const copy = [...planDraft.value]
  const [item] = copy.splice(index, 1)
  if (item) copy.splice(target, 0, item)
  planDraft.value = copy
}

function addPlanPackage() {
  const suffix = planDraft.value.length + 1
  planDraft.value.push({ packageKey: `NEW-${suffix}`, title: '新增工作包', objective: '描述新增工作包目标', dependencies: [], requirementRefs: [] })
}

function splitPlanPackage(index: number) {
  const source = planDraft.value[index]
  if (!source || planDraft.value.length >= 6) return
  const copy = { ...source, packageKey: `${source.packageKey}-B`, title: `${source.title}（拆分）`,
    sourcePackageRunIds: [...(source.sourcePackageRunIds || (source.sourcePackageRunId ? [source.sourcePackageRunId] : []))],
    dependencies: [...source.dependencies], requirementRefs: [...source.requirementRefs] }
  planDraft.value.splice(index + 1, 0, copy)
}

function mergePlanPackage(index: number) {
  if (index < 1) return
  const source = planDraft.value[index]
  const target = planDraft.value[index - 1]
  if (!source || !target) return
  const ids = (item: RollingPlanPackage) => item.sourcePackageRunIds || (item.sourcePackageRunId ? [item.sourcePackageRunId] : [])
  target.title = `${target.title} + ${source.title}`
  target.objective = `${target.objective}\n${source.objective}`
  target.sourcePackageRunIds = [...new Set([...ids(target), ...ids(source)])]
  target.dependencies = [...new Set([...target.dependencies, ...source.dependencies])]
  target.requirementRefs = [...new Set([...target.requirementRefs, ...source.requirementRefs])]
  planDraft.value.splice(index, 1)
}

async function confirmReplan() {
  const wb = workbench.value
  const anchor = selected.value
  if (!wb || !anchor || !planDraft.value.length) return
  busy.value = true
  try {
    const versions = { expectedTaskVersion: wb.taskVersion, expectedPackageRunId: anchor.id,
      expectedPackageVersion: anchor.version, expectedDiscussionRevision: anchor.discussionRevision,
      expectedDesignRevision: anchor.designRevision }
    const proposal = await api.proposeRollingPlan(props.task.id, { ...versions, packages: planDraft.value })
    await ElMessageBox.confirm(`服务端影响预览：\n${pretty(proposal.impactJson)}`, `确认计划 R${proposal.revision}？`, {
      type: 'warning', confirmButtonText: '替换未执行包', cancelButtonText: '保留原计划',
    })
    await api.confirmRollingPlan(props.task.id, proposal.id, {
      ...versions, expectedProposalVersion: proposal.version,
    })
    replanOpen.value = false; emit('refresh'); await load()
  } catch (cause) {
    if (cancelled(cause)) return
    await reportActionFailure(cause, '剩余拆包调整失败', anchor.id)
  } finally { busy.value = false }
}

function waitForNextPlanPoll() {
  return new Promise(resolve => window.setTimeout(resolve, 1000))
}

async function aiReplan() {
  const wb = workbench.value
  const anchor = selected.value
  if (!wb || !anchor || busy.value) return
  busy.value = true
  error.value = ''
  const versions = { expectedTaskVersion: wb.taskVersion, expectedPackageRunId: anchor.id,
    expectedPackageVersion: anchor.version, expectedDiscussionRevision: anchor.discussionRevision,
    expectedDesignRevision: anchor.designRevision }
  try {
    let proposal = await api.suggestRollingPlan(props.task.id, versions)
    for (let attempt = 0; proposal.state === 'GENERATING' && attempt < 900; attempt++) {
      const revisions = await api.getRollingPlanRevisions(props.task.id)
      proposal = revisions.find(item => item.id === proposal.id) || proposal
      if (proposal.state === 'GENERATING') await waitForNextPlanPoll()
    }
    if (proposal.state === 'FAILED') {
      throw new Error(`${proposal.lastErrorCode || 'PACKAGE_PLAN_SUGGESTION_FAILED'}: ${proposal.lastErrorDetail || 'AI 建议生成失败'}`)
    }
    if (proposal.state !== 'PROPOSED') throw new Error('PACKAGE_PLAN_SUGGESTION_TIMEOUT: AI 建议仍在生成，可稍后重试')
    await ElMessageBox.confirm(`AI 只读建议已完成，服务端影响预览：\n${pretty(proposal.impactJson)}`,
      `确认 AI 计划 R${proposal.revision}？`, { type: 'warning', confirmButtonText: '替换未执行包',
        cancelButtonText: '保留原计划' })
    await api.confirmRollingPlan(props.task.id, proposal.id, { ...versions, expectedProposalVersion: proposal.version })
    emit('refresh'); await load()
  } catch (cause) {
    if (cancelled(cause)) return
    await reportActionFailure(cause, 'AI 剩余拆包建议失败', anchor.id)
  } finally { busy.value = false }
}

async function addCorrection() {
  if (!selected.value || selected.value.state !== 'FACT_FROZEN' || !workbench.value) return
  try {
    const title = await ElMessageBox.prompt('修正包会追加，不会改写已冻结包。', '新增修正包', { inputPlaceholder: `修正 ${selected.value.title}` })
    const objective = await ElMessageBox.prompt('说明需要修正的已冻结行为。', '修正目标', { inputType: 'textarea' })
    const proposal = await api.addRollingCorrection(props.task.id, {
      expectedTaskVersion: workbench.value.taskVersion, correctionOfPackageRunId: selected.value.id,
      expectedPackageVersion: selected.value.version,
      expectedDiscussionRevision: selected.value.discussionRevision,
      expectedDesignRevision: selected.value.designRevision,
      title: title.value, objective: objective.value,
    })
    await ElMessageBox.confirm(`服务端影响预览：\n${pretty(proposal.impactJson)}`, '确认追加修正包？', { type: 'warning' })
    await api.confirmRollingPlan(props.task.id, proposal.id, {
      expectedTaskVersion: workbench.value.taskVersion, expectedPackageRunId: selected.value.id,
      expectedPackageVersion: selected.value.version,
      expectedDiscussionRevision: selected.value.discussionRevision,
      expectedDesignRevision: selected.value.designRevision, expectedProposalVersion: proposal.version,
    })
    emit('refresh'); await load()
  } catch (cause) {
    if (cancelled(cause)) return
    error.value = userFacingError(cause, '修正包创建失败')
  }
}

onMounted(() => void load())
watch(() => props.task.updatedAt, () => void load(selectedId.value))
</script>

<template>
  <section class="rolling-shell card card-pad" aria-labelledby="rolling-heading">
    <header class="rolling-header">
      <div><p class="eyebrow">逐包闭环</p><h2 id="rolling-heading" class="card-title">已冻结 {{ workbench?.frozenPackageCount ?? 0 }}/{{ workbench?.plannedPackageCount ?? 0 }} 包</h2></div>
      <span class="plan-chip">计划 R{{ workbench?.planRevision ?? 1 }}</span>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="snapshotChanged" title="工作包状态已更新，操作已按最新服务端状态刷新。" type="info" :closable="false" show-icon />
    <el-alert :title="policyCopy" :type="workbench?.workspacePolicy === 'PINNED_DIRECT' ? 'warning' : 'info'" :closable="false" show-icon />
    <div v-if="workbench" class="mobile-package-select">
      <el-select v-model="selectedId" aria-label="选择工作包" @change="load(selectedId)">
        <el-option v-for="(item, index) in workbench.packages" :key="item.id" :value="item.id" :label="`第 ${index + 1} 包 · ${item.title}`" />
      </el-select>
    </div>
    <div v-if="workbench" class="rolling-grid">
      <nav class="package-nav" aria-label="工作包导航">
        <button v-for="(item, index) in workbench.packages" :key="item.id" type="button" :class="{ active: item.id === selectedId }" @click="selectPackage(item)">
          <span>第 {{ index + 1 }} 包</span><strong>{{ item.title }}</strong><StatusBadge :status="item.state" />
        </button>
      </nav>
      <article v-if="selected && detail" class="package-workspace">
        <header><div><p class="eyebrow">当前工作区</p><h3>{{ selected.title }}</h3></div><StatusBadge :status="selected.state" /></header>
        <p class="objective">{{ detail.objective }}</p>
        <div class="package-phases"><span>设计</span><Icon icon="lucide:chevron-right" /><span>执行</span><Icon icon="lucide:chevron-right" /><span>事实冻结</span></div>
        <MarkdownDocument v-if="detail.designMarkdown" :content="detail.designMarkdown" collapsible />
        <p v-else class="empty-copy">详细设计尚未生成。</p>
        <div v-if="current" class="package-actions">
          <el-button v-if="capabilities?.canApproveDesign" type="primary" :loading="busy" @click="act('approve')">确认本包设计</el-button>
          <el-button v-if="capabilities?.canStartPackage" type="success" :loading="busy" @click="act('start')">开始本包执行</el-button>
          <el-button v-if="capabilities?.canRedesignPackage" plain :loading="busy" @click="act('redesign')">重新设计当前包</el-button>
          <el-button v-if="capabilities?.canRetryPackage && selected.waitingReasonCode === 'PACKAGE_CHECKPOINT_BLOCKED'" type="warning" plain :loading="busy" @click="act('checkpoint')">重新检查并释放租约</el-button>
          <el-button v-if="capabilities?.canReplanRemaining" type="primary" plain :loading="busy" @click="aiReplan">AI 调整剩余拆包</el-button>
          <el-button v-if="capabilities?.canReplanRemaining" plain :loading="busy" @click="openReplan">人工调整剩余拆包</el-button>
        </div>
        <div v-if="current && selected.waitingReasonCode === 'PACKAGE_EXECUTION_FAILED'" class="failure-actions">
          <el-button type="warning" :loading="busy" @click="resolveFailure('CONTINUE_CANDIDATE')">继续失败候选</el-button>
          <el-button plain :loading="busy" @click="resolveFailure('REDESIGN_FROM_PREVIOUS')">回到上一事实点重新设计</el-button>
          <el-button type="danger" plain :loading="busy" @click="resolveFailure('ABANDON_TASK')">放弃当前任务</el-button>
        </div>
        <div v-if="current && capabilities?.canDiscuss" class="feedback-box">
          <el-input v-model="feedback" type="textarea" :rows="3" maxlength="12000" show-word-limit placeholder="只修改当前工作包，不会重开整体需求" />
          <el-button :disabled="!feedback.trim()" :loading="busy" @click="act('discuss')">发送包级反馈</el-button>
        </div>
        <el-alert v-if="current && !capabilities" title="服务端未返回完整能力字段，工作包操作已安全关闭。" type="error" :closable="false" />
      </article>
      <aside v-if="detail" class="fact-column">
        <section class="fact-card proven"><p class="eyebrow">已证明</p><pre>{{ pretty(detail.fact?.provenJson) }}</pre></section>
        <section class="fact-card accepted"><p class="eyebrow">已接受合同</p><pre>{{ pretty(detail.fact?.acceptedContractJson) }}</pre></section>
        <section class="fact-card navigation"><p class="eyebrow">AI 导航摘要 · 非证据</p><p>{{ detail.fact?.navigationSummary || detail.handoffSummary || '尚未形成' }}</p></section>
        <el-button v-if="selected?.state === 'FACT_FROZEN' && workbench?.packageCapabilities.canAddCorrectionPackage" plain @click="addCorrection">新增修正包</el-button>
      </aside>
    </div>
    <el-dialog v-model="replanOpen" title="调整未执行工作包" width="min(760px, 92vw)">
      <p class="objective">已冻结包不会被修改。下面只编辑剩余后缀；确认前先生成服务端影响预览。</p>
      <div class="plan-editor">
        <article v-for="(item, index) in planDraft" :key="`${item.packageKey}-${index}`">
          <el-input v-model="item.packageKey" aria-label="工作包编号" />
          <el-input v-model="item.title" aria-label="工作包标题" />
          <el-input v-model="item.objective" type="textarea" :rows="2" aria-label="工作包目标" />
          <el-select v-model="item.dependencies" multiple filterable allow-create default-first-option
            aria-label="依赖工作包" placeholder="依赖编号，可多选">
            <el-option v-for="candidate in planDraft.filter(candidate => candidate.packageKey !== item.packageKey)"
              :key="candidate.packageKey" :label="candidate.packageKey" :value="candidate.packageKey" />
          </el-select>
          <div><el-button size="small" :disabled="index === 0" @click="movePlan(index, -1)">上移</el-button><el-button size="small" :disabled="index === planDraft.length - 1" @click="movePlan(index, 1)">下移</el-button><el-button size="small" :disabled="planDraft.length >= 6" @click="splitPlanPackage(index)">拆分</el-button><el-button size="small" :disabled="index === 0" @click="mergePlanPackage(index)">并入上一个</el-button><el-button size="small" type="danger" plain @click="planDraft.splice(index, 1)">删除</el-button></div>
        </article>
      </div>
      <el-button plain @click="addPlanPackage">新增工作包</el-button>
      <template #footer><el-button @click="replanOpen = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!planDraft.length" @click="confirmReplan">预览并确认</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.rolling-shell { margin-top: 16px; border-color: rgb(34 211 238 / 24%); }
.rolling-header, .package-workspace > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.plan-chip { padding: 6px 9px; border: 1px solid var(--color-border-default); border-radius: 999px; color: var(--color-accent); font: 700 10px/1 var(--font-code); }
.rolling-grid { display: grid; grid-template-columns: minmax(170px, .55fr) minmax(380px, 1.45fr) minmax(280px, 1fr); gap: 12px; margin-top: 14px; }
.package-nav { display: grid; align-content: start; gap: 7px; }
.package-nav button { display: grid; grid-template-columns: 1fr auto; gap: 5px; width: 100%; padding: 10px; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(2 6 23 / 35%); color: var(--color-text-primary); text-align: left; cursor: pointer; }
.package-nav button.active { border-color: rgb(34 211 238 / 55%); background: rgb(34 211 238 / 7%); }
.package-nav span { color: var(--color-text-tertiary); font-size: 9px; }.package-nav strong { grid-column: 1 / -1; font-size: 11px; }
.package-workspace, .fact-card { padding: 13px; border: 1px solid var(--color-border-default); border-radius: 10px; background: rgb(2 6 23 / 26%); }
.package-workspace h3 { margin: 3px 0 0; font-size: 15px; }.objective, .empty-copy { color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; }
.package-phases { display: flex; align-items: center; gap: 6px; margin: 10px 0; color: var(--color-text-tertiary); font: 9px/1 var(--font-code); }
.package-actions, .failure-actions, .feedback-box { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }.feedback-box { align-items: flex-end; }.feedback-box .el-textarea { flex: 1 1 280px; }
.fact-column { display: grid; align-content: start; gap: 9px; }.fact-card pre, .fact-card p:last-child { max-height: 220px; margin: 8px 0 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--color-text-secondary); font: 9px/1.55 var(--font-code); }.fact-card.proven { border-color: rgb(34 197 94 / 28%); }.fact-card.accepted { border-color: rgb(99 102 241 / 30%); }.fact-card.navigation { border-style: dashed; }
.mobile-package-select { display: none; margin-top: 12px; }
.plan-editor { display: grid; gap: 8px; margin: 12px 0; }.plan-editor article { display: grid; grid-template-columns: 120px minmax(160px, .7fr) minmax(220px, 1.3fr) auto; gap: 8px; align-items: start; padding: 9px; border: 1px solid var(--color-border-default); border-radius: 8px; }.plan-editor article > div:last-child { display: flex; gap: 4px; }
@media (max-width: 980px) { .rolling-grid { grid-template-columns: 1fr; }.package-nav { display: none; }.mobile-package-select { display: block; }.fact-column { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .plan-editor article { grid-template-columns: 1fr; } }
</style>
