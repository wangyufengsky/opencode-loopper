<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { Icon } from '@iconify/vue'
import { workflowRuns } from '@/api/workflowRuns'
import WorkflowContextPanel from '@/components/workflow/WorkflowContextPanel.vue'
import WorkflowNodeList from '@/components/workflow/WorkflowNodeList.vue'
import WorkflowAddMenu from '@/components/workflow/WorkflowAddMenu.vue'
import WorkflowCanvas from '@/components/workflow/WorkflowCanvas.vue'
import WorkflowPresetPicker from '@/components/workflow/WorkflowPresetPicker.vue'
import { replaceReviewSource } from '@/components/workflow/reviewSource'
import WorkflowNodeEditor from '@/components/workflow/WorkflowNodeEditor.vue'
import WorkflowNodeRun from '@/components/workflow/WorkflowNodeRun.vue'
import WorkflowPlanDiff from '@/components/workflow/WorkflowPlanDiff.vue'
import WorkflowCandidates from '@/components/workflow/WorkflowCandidates.vue'
import WorkflowFinish from '@/components/workflow/WorkflowFinish.vue'
import WorkflowPublication from '@/components/workflow/WorkflowPublication.vue'
import WorkflowSaveTemplate from '@/components/workflow/WorkflowSaveTemplate.vue'
import { protectedNodes } from '@/components/workflow/planEditing'
import WorkflowModelChoice from '@/components/workflow/WorkflowModelChoice.vue'
import { useWorkflowModel } from '@/components/workflow/modelChoice'
import WorkflowPublicInputs from '@/components/workflow/WorkflowPublicInputs.vue'
import WorkflowValueFields from '@/components/workflow/WorkflowValueFields.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { autoLayout, clone, connect, emptyGraph, emptyLayout, newNode, outcomeTitle, removeNode } from '@/components/workflow/graph'
import { useWorkflowCommand } from '@/components/workflow/command'
import { preparePlanSave, savePlan, type WorkflowPlanSave } from '@/components/workflow/planSave'
import { readValues } from '@/components/workflow/values'
import { userFacingError, workflowReasonLabel, workflowStateLabel } from '@/utils/displayLabels'
import type { WorkflowCandidate, WorkflowExecution, WorkflowGraph, WorkflowLayout, WorkflowNode, WorkflowRequirement, WorkflowRunMode } from '@/types/domain'
import '@/components/workflow/workflow.css'
import '@/components/workflow/studio.css'

const surface = ref<'none' | 'flow' | 'add' | 'tools' | 'nodes'>('none'), canvas = ref<InstanceType<typeof WorkflowCanvas>>()
const publicationBusy = ref(false), publicationPanel = ref<InstanceType<typeof WorkflowPublication>>()
const uploadBusy = ref(false), uploadPending = ref(false), presetsOpen = ref(false), finishBusy = ref(false), finishPanel = ref<InstanceType<typeof WorkflowFinish>>()
const exportDraft = ref<{ requirement: string; revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout } | null>(null), exportPanel = ref<InstanceType<typeof WorkflowSaveTemplate>>()
const route = useRoute(), command = useWorkflowCommand(() => String(route.params.id))
const base = ref<WorkflowRequirement | null>(null), snapshot = ref<WorkflowExecution | null>(null), graph = ref(emptyGraph()), layout = ref(emptyLayout())
const selected = ref(''), edgeId = ref(''), connecting = ref(''), roleNames = ref<Record<string, string>>({}), inputValues = ref<Record<string, string>>({}), checked = ref<string[]>([])
const modelChoice = useWorkflowModel(), { model, loading: modelLoading, error: modelError } = modelChoice
const loading = ref(false), readable = ref(false), error = ref(''), notice = ref(''), refreshing = ref(false), nodeBusy = ref(false), planSave = ref<WorkflowPlanSave | null>(null), inspector = ref<InstanceType<typeof WorkflowNodeRun>>()
const editing = ref(false), proposal = ref<WorkflowCandidate | null>(null), reviewOpen = ref(false), candidateBusy = ref(false), candidates = ref<InstanceType<typeof WorkflowCandidates>>()
const undo = ref<Array<{ graph: WorkflowGraph; layout: WorkflowLayout }>>([]), redo = ref<typeof undo.value>([])
let alive = true, generation = 0, refreshGeneration = 0, timer: ReturnType<typeof setTimeout> | undefined
const id = computed(() => String(route.params.id)), control = computed(() => snapshot.value?.control), state = computed(() => snapshot.value?.execution.state || base.value?.state)
const terminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(state.value || ''))
const usesModel = computed(() => graph.value.nodes.some(item => item.kind === 'WORK'))
const ending = computed(() => state.value === 'STOPPING')
const beforeStart = computed(() => ['PLANNING', 'PENDING_START'].includes(state.value || ''))
const proposalReadOnly = computed(() => !!proposal.value && (proposal.value.state !== 'PENDING' || proposal.value.stale || proposal.value.baseRevision !== base.value?.revision || terminal.value || ending.value))
const planning = computed(() => (beforeStart.value || editing.value) && !proposalReadOnly.value && !ending.value)
const protectedKeys = computed(() => base.value && !beforeStart.value ? protectedNodes(base.value.graph, snapshot.value?.execution.nodes || [], proposal.value) : new Set<string>())
const proposalReady = computed(() => !proposal.value || proposal.value.sourceCompleted || snapshot.value?.execution.nodes.some(node => node.nodeKey === proposal.value!.nodeKey && node.latestAttemptId === proposal.value!.attemptId && node.state === 'SUCCEEDED'))
const graphDirty = computed(() => !!base.value && JSON.stringify(graph.value) !== JSON.stringify(base.value.graph))
const layoutDirty = computed(() => !!base.value && JSON.stringify(layout.value) !== JSON.stringify(base.value.layout))
const dirty = computed(() => !proposalReadOnly.value && (graphDirty.value || layoutDirty.value || !!planSave.value || !!proposal.value))
const operationsLocked = computed(() => !!exportDraft.value || command.locked.value || loading.value || !readable.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value || !!planSave.value)
const locked = computed(() => operationsLocked.value || uploadBusy.value || uploadPending.value)
const node = computed(() => graph.value.nodes.find(value => value.id === selected.value)), edge = computed(() => graph.value.edges.find(value => value.id === edgeId.value))
const summary = computed(() => snapshot.value?.execution.nodes.find(value => value.nodeKey === selected.value))
const statuses = computed(() => Object.fromEntries((snapshot.value?.execution.nodes || []).map(value => [value.nodeKey, value.state])))
const checkpoints = computed(() => control.value?.checkpoints || []), checkpointsReady = computed(() => checkpoints.value.every(value => checked.value.includes(value.attemptId)))
const executable = computed(() => !!base.value && !locked.value && !editing.value && !proposal.value && !graphDirty.value && control.value?.reasonCode !== 'WORKFLOW_PLAN_REVIEW_REQUIRED' && checkpointsReady.value && ['PENDING_START', 'RUNNING', 'PAUSED', 'STALLED'].includes(state.value || ''))
const inputsFrozen = computed(() => !!control.value?.configured || snapshot.value?.execution.nodes.some(value => value.attemptCount > 0))
const historyDateFields = computed(() => [...new Set(graph.value.nodes.filter(node => ['system.git.history', 'system.review.snapshot'].includes(node.moduleId || '')).flatMap(node => node.inputs.filter(input => ['startDate', 'endDate'].includes(input.name) && input.source === 'REQUIREMENT').map(input => input.sourceId)))])
const repositoryFields = computed(() => [...new Set(graph.value.nodes.filter(node => ['system.repository.snapshot', 'system.git.history', 'system.review.snapshot'].includes(node.moduleId || '')).flatMap(node => node.inputs.filter(input => input.name === 'branch' && input.source === 'REQUIREMENT').map(input => input.sourceId)))])
function accept(value: WorkflowRequirement) { base.value = value; graph.value = clone(value.graph); layout.value = clone(value.layout); planSave.value = null; editing.value = false; proposal.value = null; undo.value = []; redo.value = [] }
function schedule() { clearTimeout(timer); if (alive) timer = setTimeout(() => { if (document.hidden || command.locked.value || nodeBusy.value || candidateBusy.value || uploadBusy.value || uploadPending.value) schedule(); else void refresh() }, 2500) }
async function refresh(strict = false, current = command.captureScope()) {
  if (!current() || refreshing.value && !strict) return
  const ticket = generation, refreshTicket = ++refreshGeneration, requirement = id.value; refreshing.value = true
  const currentRead = () => current() && ticket === generation && refreshTicket === refreshGeneration
  try {
    const value = await workflowRuns.execution(requirement)
    if (!currentRead() || uploadBusy.value || uploadPending.value) return
    if (base.value && value.execution.revision !== base.value.revision) {
      if (dirty.value) throw new Error('计划已有新版本，当前草稿已保留，请重新加载后继续。')
      const updated = await workflowRuns.get(requirement); if (!currentRead() || uploadBusy.value || uploadPending.value) return; accept(updated)
    }
    snapshot.value = value; readable.value = true; error.value = ''; checked.value = checked.value.filter(key => value.control.checkpoints.some(checkpoint => checkpoint.attemptId === key))
    modelChoice.adoptControl(value.control.model)
  } catch (failure) { if (currentRead()) { readable.value = false; error.value = userFacingError(failure, '执行状态暂时无法读取，请重试。'); if (strict) throw failure } }
  finally { if (currentRead()) { refreshing.value = false; schedule() } }
}
async function load() {
  command.invalidate()
  const ticket = ++generation, requirement = id.value, current = command.captureScope(); uploadBusy.value = false; uploadPending.value = false; surface.value = 'none'; connecting.value = ''; presetsOpen.value = false; refreshGeneration++; clearTimeout(timer); loading.value = true; readable.value = false; refreshing.value = false; snapshot.value = null; base.value = null; editing.value = false; proposal.value = null; reviewOpen.value = false; selected.value = ''; edgeId.value = ''; checked.value = []; modelChoice.reset(); inputValues.value = {}; error.value = ''; notice.value = ''
  const currentLoad = () => current() && ticket === generation
  try { const value = await workflowRuns.get(requirement); if (!currentLoad()) return; accept(value); await refresh(true, current) }
  catch (failure) { if (currentLoad()) error.value = userFacingError(failure, '需求计划无法读取，请重新加载。') }
  finally { if (currentLoad()) loading.value = false }
}
function change(next: WorkflowGraph, nextLayout = layout.value) {
  if (!planning.value || locked.value) return
  undo.value.push(clone({ graph: graph.value, layout: layout.value })); if (undo.value.length > 100) undo.value.shift(); redo.value = []; graph.value = next; layout.value = nextLayout
}
function history(back: boolean) { if (locked.value || !planning.value) return; const from = back ? undo : redo, to = back ? redo : undo, item = from.value.pop(); if (item) { to.value.push(clone({ graph: graph.value, layout: layout.value })); graph.value = item.graph; layout.value = item.layout } }
function canChangeContext() { return !uploadBusy.value && !uploadPending.value && !nodeBusy.value && (!inspector.value || inspector.value.canLeave()) }
function select(key: string) {
  if (selected.value === key || !canChangeContext()) return
  selected.value = key; edgeId.value = ''; surface.value = 'none'; presetsOpen.value = false
}
function selectEdge(key: string) {
  if (edgeId.value === key || !canChangeContext()) return
  edgeId.value = key; selected.value = ''; connecting.value = ''; surface.value = 'none'; presetsOpen.value = false
}
function dismiss() {
  if (!canChangeContext()) return
  const previous = selected.value
  selected.value = ''; edgeId.value = ''; connecting.value = ''; surface.value = 'none'; presetsOpen.value = false
  void nextTick(() => canvas.value?.focus(previous))
}
function toggle(panel: 'flow' | 'add' | 'tools' | 'nodes') {
  if (!canChangeContext()) return
  surface.value = surface.value === panel ? 'none' : panel
  selected.value = ''; edgeId.value = ''; connecting.value = ''; presetsOpen.value = false
}
function locate(key: string) { select(key); if (selected.value === key) void nextTick(() => { canvas.value?.reveal(key); canvas.value?.focus(key) }) }
function openPresets() { surface.value = 'none'; presetsOpen.value = true }
watch(graph, value => {
  if (selected.value && !value.nodes.some(item => item.id === selected.value) || edgeId.value && !value.edges.some(item => item.id === edgeId.value)) dismiss()
  if (connecting.value && !value.nodes.some(item => item.id === connecting.value)) connecting.value = ''
})
function add(module: 'free.readonly' | 'free.write' | 'human') { if (!planning.value || locked.value) return; const next = newNode(module); change({ ...graph.value, nodes: [...graph.value.nodes, next] }, { ...layout.value, positions: { ...layout.value.positions, [next.id]: { x: 40, y: graph.value.nodes.length * 150 } } }); locate(next.id) }
function addPreset(next: WorkflowGraph, node: WorkflowNode) {
  if (!planning.value || locked.value) return
  change(next, { ...layout.value, positions: { ...layout.value.positions, [node.id]: autoLayout(next)[node.id]! } }); locate(node.id); presetsOpen.value = false
}
function patch(value: WorkflowNode) { if (protectedKeys.value.has(value.id)) return; change(replaceReviewSource(graph.value, value, !inputsFrozen.value)) }
function join(key: string) { if (!planning.value || locked.value) return; if (!connecting.value) { connecting.value = key; return }; try { if (protectedKeys.value.has(key)) throw new Error('这个节点已执行或属于保留区域，不能改变它的前置依赖。'); change(connect(graph.value, connecting.value, key)); connecting.value = ''; error.value = '' } catch (failure) { error.value = userFacingError(failure) } }
function joinPair(from: string, to: string) {
  if (!planning.value || locked.value || !canChangeContext()) return
  try {
    if (protectedKeys.value.has(to)) throw new Error('这个节点已执行或属于保留区域，不能改变它的前置依赖。')
    change(connect(graph.value, from, to)); connecting.value = ''; error.value = ''
  }
  catch (failure) { error.value = userFacingError(failure, '节点无法连接，请检查依赖顺序。') }
}
function remove(key: string) { if (!planning.value || locked.value || !canRemove(key) || !window.confirm('将节点及其连接移出当前计划？已有执行和交付物历史将保留。')) return; try { const next = removeNode(graph.value, key), position = clone(layout.value); delete position.positions[key]; change(next, position); dismiss() } catch (failure) { error.value = userFacingError(failure) } }
function removeEdge() { if (!locked.value && planning.value && edge.value && !protectedKeys.value.has(edge.value.to) && window.confirm('删除这条连接？')) { change({ ...graph.value, edges: graph.value.edges.filter(item => item.id !== edgeId.value) }); dismiss() } }
function setLayout(value: WorkflowLayout) { if (!locked.value) { if (planning.value) change(graph.value, value); else layout.value = value } }
function exportTemplate() {
  if (!base.value || locked.value || proposal.value || inspector.value && !inspector.value.canLeave() || candidates.value && !candidates.value.canLeave()) return
  exportDraft.value = { requirement: id.value, revision: base.value.revision, title: base.value.title, graph: clone(graph.value), layout: clone(layout.value) }
}
async function save() {
  if (!base.value || uploadBusy.value || uploadPending.value || exportDraft.value || command.locked.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value || !readable.value || proposalReadOnly.value || !proposalReady.value) return
  if (!planSave.value) planSave.value = preparePlanSave({ ...base.value, version: snapshot.value!.execution.version, state: snapshot.value!.execution.state }, graph.value, layout.value, proposal.value)
  const operation = planSave.value, current = command.captureScope()
  await command.submit('保存计划', () => savePlan(operation), async value => {
    if (!current()) return
    accept(value); reviewOpen.value = false; await refresh(true, current)
    if (current()) notice.value = operation.active && operation.changesPlan ? '计划已应用，请选择执行方式继续。' : '计划已保存。'
  })
}
async function confirm() {
  if (!base.value || locked.value || graphDirty.value || state.value !== 'PLANNING') return
  const body = { requestKey: crypto.randomUUID(), expectedVersion: snapshot.value!.execution.version }, target = id.value, retainedLayout = layoutDirty.value ? clone(layout.value) : null, current = command.captureScope()
  await command.submit('确认计划', () => workflowRuns.confirm(target, body), async () => {
    if (!current()) return
    const value = await workflowRuns.get(target)
    if (!current()) return
    accept(value); if (retainedLayout) layout.value = retainedLayout; await refresh(true, current)
    if (current()) notice.value = '计划已确认，选择执行方式即可开始。'
  })
}
function waitingForModel(mode: WorkflowRunMode, target: string | null = null) { return modelLoading.value && (mode !== 'SINGLE' || graph.value.nodes.find(node => node.id === target)?.kind === 'WORK') }
async function run(mode: WorkflowRunMode, target: string | null = null) {
  if (!executable.value || !control.value || waitingForModel(mode, target)) return
  try {
    if (mode !== 'CONTINUOUS' && !target) throw new Error('请先选择一个节点。')
    const body = { requestKey: crypto.randomUUID(), expectedVersion: control.value.version, expectedControlVersion: control.value.controlVersion, mode, targetKey: target,
      inputs: inputsFrozen.value ? null : readValues(graph.value.inputs, inputValues.value), model: clone(model.value), checkpointAttempts: checkpoints.value.map(checkpoint => checkpoint.attemptId) }
    const targetId = id.value, current = command.captureScope()
    await command.submit('执行流程', () => workflowRuns.start(targetId, body), async () => { await refresh(true, current); if (current()) notice.value = '' })
  } catch (failure) { error.value = userFacingError(failure) }
}
async function pause() { if (!control.value || locked.value) return; const target = id.value, body = { requestKey: crypto.randomUUID(), expectedControlVersion: control.value.controlVersion }, current = command.captureScope(); await command.submit('暂停派发', () => workflowRuns.pause(target, body), async () => { await refresh(true, current) }) }
async function cancel() { if (!base.value || !beforeStart.value || locked.value || !window.confirm('取消这个尚未执行的需求？历史计划将保留。')) return; const target = id.value, body = { requestKey: crypto.randomUUID(), expectedVersion: snapshot.value!.execution.version }, current = command.captureScope(); await command.submit('取消需求', () => workflowRuns.cancel(target, body), async () => { await refresh(true, current) }) }
function canRemove(key: string) {
  if (beforeStart.value) return true
  if (!base.value || statuses.value[key] === 'ACTIVE' || checkpoints.value.some(check => check.nodeKey === key)) return false
  const retained = (snapshot.value?.execution.nodes || []).filter(run => run.nodeKey !== key && graph.value.nodes.some(node => node.id === run.nodeKey))
  return !protectedNodes(base.value.graph, retained, proposal.value).has(key)
}
async function beginEdit() {
  if (locked.value || terminal.value || ending.value || beforeStart.value || editing.value || inspector.value && !inspector.value.canLeave()) return
  const current = command.captureScope()
  if (control.value?.configured && ['ACTIVE', 'WAITING'].includes(control.value.state)) {
    const target = id.value, body = { requestKey: crypto.randomUUID(), expectedControlVersion: control.value.controlVersion }
    await command.submit('暂停派发并调整计划', () => workflowRuns.pause(target, body), async () => { await refresh(true, current); if (current()) editing.value = true })
  } else { await refresh(false, current); if (current() && readable.value && !terminal.value) editing.value = true }
}
function toggleCandidates() { if (!candidateBusy.value && (!candidates.value || candidates.value.canLeave())) { reviewOpen.value = !reviewOpen.value; surface.value = 'none' } }
function review(value: WorkflowCandidate) {
  if (locked.value || !base.value || !canLeave()) return
  proposal.value = clone(value); graph.value = clone(value.graph); layout.value = clone(base.value.layout)
  layout.value.positions = { ...autoLayout(value.graph), ...layout.value.positions }
  for (const key of Object.keys(layout.value.positions)) if (!graph.value.nodes.some(node => node.id === key)) delete layout.value.positions[key]
  editing.value = true; reviewOpen.value = false; surface.value = 'none'; selected.value = ''; edgeId.value = ''; undo.value = []; redo.value = []
}
function discard() {
  if (locked.value || !base.value || dirty.value && !window.confirm('放弃当前画布调整，回到已经生效的计划？候选记录仍然保留。')) return
  accept(base.value); surface.value = 'none'; selected.value = ''; edgeId.value = ''; connecting.value = ''; notice.value = ''
}
function canLeave() { if (publicationPanel.value && !publicationPanel.value.canLeave()) return false; if (exportPanel.value && !exportPanel.value.canLeave()) return false; if (uploadBusy.value || uploadPending.value) return false; if (finishPanel.value && !finishPanel.value.canLeave()) return false; if (candidates.value && !candidates.value.canLeave()) return false; if (inspector.value && !inspector.value.canLeave()) return false; return !(dirty.value || command.pending.value) || window.confirm('当前计划有未保存或待确认的操作，仍要离开？') }
async function reload() { if (canLeave()) { exportDraft.value = null; planSave.value = null; await load() } }
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
const unload = (event: BeforeUnloadEvent) => { if (exportDraft.value || dirty.value || command.pending.value || uploadBusy.value || uploadPending.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { alive = false; generation++; clearTimeout(timer); window.removeEventListener('beforeunload', unload) })
watch(id, () => { exportDraft.value = null; void load() }, { immediate: true })
watch([usesModel, readable, terminal], () => { if (usesModel.value && readable.value && !terminal.value) void modelChoice.initialize() })
</script>
<template>
<main id="main-content" class="workflow-editor workflow-page workflow-requirement" @keydown.esc="dismiss">
  <header class="workflow-editor-header">
    <RouterLink to="/requirements" class="workflow-back" aria-label="返回需求任务"><Icon icon="lucide:arrow-left" /></RouterLink>
    <div class="workflow-title"><p class="eyebrow">{{ proposal ? '候选计划预览' : planning ? '流程规划' : '任务执行' }}</p><h1>{{ base?.title || '读取需求' }}</h1></div>
    <StatusBadge v-if="state" :status="state === 'COMPLETED' ? 'SUCCEEDED' : state" :label="workflowStateLabel(state)" />
    <span class="workflow-save-state" :class="{ 'is-dirty': dirty }">{{ dirty ? '未保存' : base ? `计划版本 ${base.revision}` : '' }}</span>
    <div class="workflow-inline">
      <button v-if="dirty" :disabled="uploadBusy || uploadPending || !!exportDraft || command.locked.value || nodeBusy || candidateBusy || !readable || !proposalReady" @click="save">{{ planSave ? '重试保存计划' : proposal ? '确认并应用候选计划' : editing ? '应用计划调整' : planning ? '保存计划' : '保存布局' }}</button>
      <button v-if="editing || proposal" :disabled="locked" @click="discard">{{ proposalReadOnly ? '退出候选预览' : '放弃调整' }}</button>
      <button v-if="state === 'PLANNING'" class="primary-button" :disabled="locked || graphDirty" @click="confirm"><Icon icon="lucide:check" />确认计划</button>
      <button v-if="control?.configured && ['ACTIVE', 'WAITING'].includes(control.state)" :disabled="locked" @click="pause"><Icon icon="lucide:pause" />暂停后续派发</button>
      <button v-if="control?.configured && control.state !== 'ACTIVE' && control.state !== 'DONE' && !ending && control.mode" :disabled="!executable || waitingForModel(control.mode, control.targetKey) || !!control.targetKey && !graph.nodes.some(node => node.id === control?.targetKey)" @click="run(control.mode, control.targetKey)">继续原执行范围</button>
      <button v-if="state !== 'PLANNING' && !terminal && !ending" class="primary-button" :disabled="!executable || waitingForModel('CONTINUOUS')" @click="run('CONTINUOUS')"><Icon icon="lucide:play" />连续执行</button>
    </div>
  </header>
  <div v-if="error || command.error.value" role="alert" class="workflow-error">{{ error || command.error.value }}<button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新操作结果' : '重试原操作' }}</button><button v-else @click="reload">重新加载</button></div>
  <p v-if="notice" class="workflow-notice" role="status">{{ notice }}</p><p v-if="loading" role="status">正在读取需求计划…</p>
  <p v-if="usesModel && !terminal && modelLoading" role="status" class="workflow-notice">正在读取默认执行模型…</p>
  <div v-if="usesModel && !terminal && modelError" role="alert" class="workflow-error">{{ modelError }}<button :disabled="modelLoading" @click="modelChoice.loadDefault">重试默认模型</button><button :disabled="uploadBusy || uploadPending || nodeBusy" @click="toggle('flow')">选择执行模型</button></div>
  <WorkflowSaveTemplate v-if="exportDraft" ref="exportPanel" v-bind="exportDraft" @close="exportDraft = null" />
  <WorkflowPublication v-if="base && !beforeStart && !loading" :key="id" ref="publicationPanel" @busy="value => publicationBusy = value" :requirement="id" :revision="base.revision" :controls-visible="false" :disabled="!!exportDraft || command.locked.value || !readable || dirty || editing || !!proposal" />
  <WorkflowFinish v-if="base && snapshot && !loading" :key="id" ref="finishPanel" :requirement="id" :version="snapshot.execution.version" :state="snapshot.execution.state" :controls-visible="false" :disabled="!!exportDraft || command.locked.value || nodeBusy || candidateBusy || !readable || dirty || editing || !!proposal" :before-open="() => !inspector || inspector.canLeave()" @busy="value => finishBusy = value" @changed="refresh()" />
  <section v-if="proposal" class="workflow-proposal-banner" aria-label="候选计划预览"><div><strong>{{ proposal.sourceTitle }} 提出的计划 · {{ proposalReadOnly ? '只读预览' : '尚未生效' }}</strong><p>新增 {{ proposal.changes.added.length }} 个节点，修改 {{ proposal.changes.changed.length }} 个节点，移除 {{ proposal.changes.removed.length }} 个节点。</p><WorkflowPlanDiff :before="proposal.originalGraph" :after="graph" /><p v-if="!proposalReady">来源节点尚未成功收尾，可以先查看和修改后续方案。</p><p v-if="proposalReadOnly">这里展示原始候选，当前生效计划保持原样。</p></div></section>
  <WorkflowCandidates v-if="reviewOpen && base" :key="id" ref="candidates" :requirement="id" :disabled="!!exportDraft || command.locked.value || nodeBusy || finishBusy || publicationBusy || !readable" @review="review" @changed="refresh()" @busy="value => candidateBusy = value" @close="reviewOpen = false" />
  <section v-if="base && !loading && (control?.reasonCode && control.reasonCode !== 'WORKFLOW_NOT_STARTED' || checkpoints.length || ending)" class="workflow-run-controls" aria-label="执行提示">
    <div><strong>{{ terminal ? workflowStateLabel(state) : ending ? '正在停止' : workflowStateLabel(control?.state) }}</strong><p>{{ workflowReasonLabel(control?.reasonCode) }}</p></div>
    <button v-if="control?.reasonCode === 'WORKFLOW_PLAN_REVIEW_REQUIRED'" :disabled="locked || editing || !!proposal" @click="toggleCandidates">候选计划</button>
    <div v-if="checkpoints.length" class="workflow-checkpoints"><strong>待检查的交付物</strong><label v-for="check in checkpoints" :key="check.attemptId"><input v-model="checked" type="checkbox" :value="check.attemptId" :disabled="locked" /><span>确认 {{ graph.nodes.find(item => item.id === check.nodeKey)?.title || '已完成节点' }} 的结果</span><button @click="select(check.nodeKey)">查看节点</button></label></div>
  </section>
  <div v-if="base && !loading" class="workflow-studio">
    <div class="workflow-toolbar" aria-label="画布工具">
      <button v-if="planning" :disabled="locked" :aria-expanded="surface === 'add'" @click="toggle('add')"><Icon icon="lucide:plus" />添加节点</button>
      <span v-if="planning" class="workflow-tool-divider" />
      <button :aria-expanded="surface === 'flow'" :disabled="uploadBusy || uploadPending || nodeBusy" @click="toggle('flow')"><Icon icon="lucide:sliders-horizontal" />需求与资料</button>
      <template v-if="planning && (undo.length || redo.length)"><span class="workflow-tool-divider" /><button aria-label="撤销计划修改" title="撤销计划修改" :disabled="locked || !undo.length" @click="history(true)"><Icon icon="lucide:undo-2" /></button><button aria-label="重做计划修改" title="重做计划修改" :disabled="locked || !redo.length" @click="history(false)"><Icon icon="lucide:redo-2" /></button></template>
      <button aria-label="更多工具" title="更多工具" :aria-expanded="surface === 'tools'" :disabled="uploadBusy || uploadPending || nodeBusy" @click="toggle('tools')"><Icon icon="lucide:ellipsis" /></button>
    </div>
    <WorkflowCanvas ref="canvas" :graph="graph" :layout="layout" :selected="selected" :selected-edge="edgeId" :connecting="connecting" :readonly="!planning || locked" :movable="!locked" :states="statuses" :role-names="roleNames" @select="select" @connect="join" @connect-pair="joinPair" @edge="selectEdge" @layout="setLayout" @remove="remove" @cancel="dismiss" />
    <WorkflowContextPanel v-if="surface === 'add'" title="添加节点" class="workflow-tools-panel" @close="dismiss"><WorkflowAddMenu :disabled="locked" @add="add" @presets="openPresets" /></WorkflowContextPanel>
    <WorkflowContextPanel v-else-if="surface === 'tools'" title="更多工具" class="workflow-tools-panel" @close="dismiss"><div class="workflow-tool-actions"><button @click="toggle('nodes')"><Icon icon="lucide:list-tree" />节点列表</button>
      <button :disabled="locked" @click="setLayout({ ...layout, positions: autoLayout(graph) })"><Icon icon="lucide:network" />自动排列</button>
      <button :disabled="refreshing || command.locked.value" @click="refresh()"><Icon icon="lucide:refresh-cw" />刷新状态</button>
      <button :disabled="locked || !!proposal" @click="exportTemplate"><Icon icon="lucide:copy" />另存为流程模板</button>
      <button v-if="!beforeStart && !terminal && !ending && !editing && !proposal" :disabled="locked" @click="beginEdit">调整后续计划</button>
      <button v-if="!beforeStart && control?.reasonCode !== 'WORKFLOW_PLAN_REVIEW_REQUIRED'" :disabled="locked || editing || !!proposal" @click="toggleCandidates">候选计划</button>
      <button v-if="!beforeStart" :disabled="locked" @click="publicationPanel?.toggle(); surface = 'none'">查看代码成果</button>
      <button v-if="finishPanel?.available" class="danger" :disabled="!finishPanel.editable" @click="finishPanel.show(); surface = 'none'">提前结束需求</button>
      <button v-if="beforeStart" class="danger" :disabled="locked" @click="cancel">取消需求</button>
      <p>选择节点查看设置与执行记录。拖动空白处平移，Ctrl + 滚轮缩放，Esc 取消选择。</p>
    </div></WorkflowContextPanel>
    <WorkflowContextPanel v-if="surface === 'nodes'" title="节点列表" class="workflow-tools-panel" @close="dismiss"><WorkflowNodeList :nodes="graph.nodes" :states="statuses" @select="locate" /></WorkflowContextPanel>
    <WorkflowContextPanel v-if="presetsOpen && planning" title="预设工作模块" class="workflow-preset-panel" @close="dismiss"><WorkflowPresetPicker :graph="graph" :disabled="locked" @insert="addPreset" @close="dismiss" /></WorkflowContextPanel>
    <WorkflowContextPanel v-else-if="node" :key="node.id" title="节点详情" :focus-on-open="false" :close-disabled="nodeBusy" @close="dismiss">
      <div v-if="state !== 'PLANNING' && !terminal && !ending" class="workflow-selection-actions"><button :disabled="!executable || waitingForModel('SINGLE', selected)" @click="run('SINGLE', selected)">{{ summary?.state === 'FAILED' ? '重试所选节点' : '执行所选节点' }}</button><button :disabled="!executable || waitingForModel('UNTIL', selected)" @click="run('UNTIL', selected)">运行至所选节点</button></div>
    <WorkflowNodeEditor v-if="planning || proposal" :node="node" :graph="graph" :disabled="locked || proposalReadOnly || protectedKeys.has(node.id)" :remove-disabled="locked || proposalReadOnly || !canRemove(node.id)" :readonly-reason="proposalReadOnly ? '历史候选仅供查看。' : protectedKeys.has(node.id) ? '此节点及其输入已固定，可以调整它之后尚未执行的工作。' : undefined" @change="patch" @remove="remove(node.id)" @role-label="(key, title) => roleNames[key] = title" />
    <WorkflowNodeRun v-else :key="`${id}-${node.id}`" ref="inspector" :requirement="id" :version="snapshot?.execution.version ?? base.version" :node="node" :summary="summary" :locked="!!exportDraft || command.locked.value || finishBusy || publicationBusy || !readable || ending && node.kind === 'HUMAN'" @busy="value => nodeBusy = value" @changed="refresh()" />
    </WorkflowContextPanel>
    <WorkflowContextPanel v-else-if="edge" title="连接设置" @close="dismiss"><aside class="workflow-inspector" aria-label="连接设置"><p class="workflow-edge-route">{{ graph.nodes.find(item => item.id === edge?.from)?.title }} <Icon icon="lucide:arrow-right" /> {{ graph.nodes.find(item => item.id === edge?.to)?.title }}</p><fieldset class="workflow-fields" :disabled="!planning || locked || protectedKeys.has(edge.to)"><label>执行条件<select :value="edge.outcome || ''" @change="change({ ...graph, edges: graph.edges.map(item => item.id === edge?.id ? { ...item, outcome: ($event.target as HTMLSelectElement).value || null } : item) })"><option value="">前置节点成功完成</option><option v-for="value in graph.nodes.find(item => item.id === edge?.from)?.outcomes" :key="value" :value="value">{{ outcomeTitle(graph.nodes.find(item => item.id === edge?.from), value) }}</option></select></label><button v-if="planning" class="danger" @click="removeEdge">删除连接</button></fieldset></aside></WorkflowContextPanel>
    <WorkflowContextPanel v-else-if="surface === 'flow'" title="需求与资料" :close-disabled="uploadBusy || uploadPending" @close="dismiss"><aside class="workflow-inspector" aria-label="需求与资料"><p class="workflow-objective">{{ base.objective }}</p><p v-if="!graph.inputs.length">此流程未声明额外公共资料。</p><p v-else-if="inputsFrozen">本次资料已固定，可在节点详情查看实际输入。</p><WorkflowValueFields v-else :date-fields="historyDateFields" :repository-context="{ project: base.projectId, fields: repositoryFields }" :upload-context="{ requirement: id, version: snapshot?.execution.version ?? base.version, revision: base.revision }" :fields="graph.inputs" :values="inputValues" @busy="value => uploadBusy = value" @pending="value => uploadPending = value" :disabled="operationsLocked || uploadBusy" @change="value => inputValues = value" /><details v-if="beforeStart && !inputsFrozen"><summary>调整公共资料设置</summary><WorkflowPublicInputs :graph="graph" :disabled="locked" @change="value => change(value)" /></details><p v-if="state === 'PLANNING'" class="workflow-inspector-hint">确认计划后选择执行方式。确认本身不会创建模型会话或占用工作目录。</p><WorkflowModelChoice v-if="usesModel" :model-value="model" :disabled="locked" @update:model-value="modelChoice.choose" /></aside></WorkflowContextPanel>
  </div>
</main>
</template>
