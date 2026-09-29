<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { Icon } from '@iconify/vue'
import { workflowRuns } from '@/api/workflowRuns'
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
import WorkflowPublicInputs from '@/components/workflow/WorkflowPublicInputs.vue'
import WorkflowValueFields from '@/components/workflow/WorkflowValueFields.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { autoLayout, clone, connect, emptyGraph, emptyLayout, newNode, outcomeTitle, removeNode } from '@/components/workflow/graph'
import { useWorkflowCommand } from '@/components/workflow/command'
import { preparePlanSave, savePlan, type WorkflowPlanSave } from '@/components/workflow/planSave'
import { readValues } from '@/components/workflow/values'
import { userFacingError, workflowReasonLabel, workflowStateLabel } from '@/utils/displayLabels'
import type { WorkflowCandidate, WorkflowExecution, WorkflowGraph, WorkflowLayout, WorkflowModel, WorkflowNode, WorkflowRequirement, WorkflowRunMode } from '@/types/domain'
import '@/components/workflow/workflow.css'

const publicationBusy = ref(false), publicationPanel = ref<InstanceType<typeof WorkflowPublication>>()
const uploadBusy = ref(false), presetsOpen = ref(false), finishBusy = ref(false), finishPanel = ref<InstanceType<typeof WorkflowFinish>>()
const exportDraft = ref<{ requirement: string; revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout } | null>(null), exportPanel = ref<InstanceType<typeof WorkflowSaveTemplate>>()
const route = useRoute(), command = useWorkflowCommand()
const base = ref<WorkflowRequirement | null>(null), snapshot = ref<WorkflowExecution | null>(null), graph = ref(emptyGraph()), layout = ref(emptyLayout())
const selected = ref(''), edgeId = ref(''), connecting = ref(''), roleNames = ref<Record<string, string>>({}), model = ref<WorkflowModel | null>(null), inputValues = ref<Record<string, string>>({}), checked = ref<string[]>([])
const loading = ref(false), readable = ref(false), error = ref(''), notice = ref(''), refreshing = ref(false), nodeBusy = ref(false), planSave = ref<WorkflowPlanSave | null>(null), inspector = ref<InstanceType<typeof WorkflowNodeRun>>()
const editing = ref(false), proposal = ref<WorkflowCandidate | null>(null), reviewOpen = ref(false), candidateBusy = ref(false), candidates = ref<InstanceType<typeof WorkflowCandidates>>()
const undo = ref<Array<{ graph: WorkflowGraph; layout: WorkflowLayout }>>([]), redo = ref<typeof undo.value>([])
let alive = true, generation = 0, refreshGeneration = 0, timer: ReturnType<typeof setTimeout> | undefined
const id = computed(() => String(route.params.id)), control = computed(() => snapshot.value?.control), state = computed(() => snapshot.value?.execution.state || base.value?.state)
const terminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(state.value || ''))
const ending = computed(() => state.value === 'STOPPING')
const beforeStart = computed(() => ['PLANNING', 'PENDING_START'].includes(state.value || ''))
const proposalReadOnly = computed(() => !!proposal.value && (proposal.value.state !== 'PENDING' || proposal.value.stale || proposal.value.baseRevision !== base.value?.revision || terminal.value || ending.value))
const planning = computed(() => (beforeStart.value || editing.value) && !proposalReadOnly.value && !ending.value)
const protectedKeys = computed(() => base.value && !beforeStart.value ? protectedNodes(base.value.graph, snapshot.value?.execution.nodes || [], proposal.value) : new Set<string>())
const proposalReady = computed(() => !proposal.value || proposal.value.sourceCompleted || snapshot.value?.execution.nodes.some(node => node.nodeKey === proposal.value!.nodeKey && node.latestAttemptId === proposal.value!.attemptId && node.state === 'SUCCEEDED'))
const graphDirty = computed(() => !!base.value && JSON.stringify(graph.value) !== JSON.stringify(base.value.graph))
const layoutDirty = computed(() => !!base.value && JSON.stringify(layout.value) !== JSON.stringify(base.value.layout))
const dirty = computed(() => !proposalReadOnly.value && (graphDirty.value || layoutDirty.value || !!planSave.value || !!proposal.value))
const locked = computed(() => !!exportDraft.value || uploadBusy.value || command.locked.value || loading.value || !readable.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value || !!planSave.value)
const node = computed(() => graph.value.nodes.find(value => value.id === selected.value)), edge = computed(() => graph.value.edges.find(value => value.id === edgeId.value))
const summary = computed(() => snapshot.value?.execution.nodes.find(value => value.nodeKey === selected.value))
const statuses = computed(() => Object.fromEntries((snapshot.value?.execution.nodes || []).map(value => [value.nodeKey, value.state])))
const checkpoints = computed(() => control.value?.checkpoints || []), checkpointsReady = computed(() => checkpoints.value.every(value => checked.value.includes(value.attemptId)))
const executable = computed(() => !!base.value && !locked.value && !editing.value && !proposal.value && !graphDirty.value && control.value?.reasonCode !== 'WORKFLOW_PLAN_REVIEW_REQUIRED' && checkpointsReady.value && ['PENDING_START', 'RUNNING', 'PAUSED', 'STALLED'].includes(state.value || ''))
const inputsFrozen = computed(() => !!control.value?.configured || snapshot.value?.execution.nodes.some(value => value.attemptCount > 0))
const historyDateFields = computed(() => [...new Set(graph.value.nodes.filter(node => ['system.git.history', 'system.review.snapshot'].includes(node.moduleId || '')).flatMap(node => node.inputs.filter(input => ['startDate', 'endDate'].includes(input.name) && input.source === 'REQUIREMENT').map(input => input.sourceId)))])
const repositoryFields = computed(() => [...new Set(graph.value.nodes.filter(node => ['system.repository.snapshot', 'system.git.history', 'system.review.snapshot'].includes(node.moduleId || '')).flatMap(node => node.inputs.filter(input => input.name === 'branch' && input.source === 'REQUIREMENT').map(input => input.sourceId)))])
function accept(value: WorkflowRequirement) { base.value = value; graph.value = clone(value.graph); layout.value = clone(value.layout); planSave.value = null; editing.value = false; proposal.value = null; undo.value = []; redo.value = [] }
function schedule() { clearTimeout(timer); if (alive) timer = setTimeout(() => { if (document.hidden || command.locked.value || nodeBusy.value || candidateBusy.value) schedule(); else void refresh() }, 2500) }
async function refresh(strict = false) {
  if (refreshing.value && !strict) return
  const ticket = generation, refreshTicket = ++refreshGeneration, requirement = id.value; refreshing.value = true
  try {
    const value = await workflowRuns.execution(requirement)
    if (!alive || ticket !== generation || refreshTicket !== refreshGeneration) return
    if (base.value && value.execution.revision !== base.value.revision) {
      if (dirty.value) throw new Error('计划已有新版本，当前草稿已保留，请重新加载后继续。')
      const updated = await workflowRuns.get(requirement); if (!alive || ticket !== generation || refreshTicket !== refreshGeneration) return; accept(updated)
    }
    snapshot.value = value; readable.value = true; error.value = ''; checked.value = checked.value.filter(key => value.control.checkpoints.some(checkpoint => checkpoint.attemptId === key))
    if (!model.value && value.control.model) model.value = clone(value.control.model)
  } catch (failure) { if (alive && ticket === generation && refreshTicket === refreshGeneration) { readable.value = false; error.value = userFacingError(failure, '执行状态暂时无法读取，请重试。'); if (strict) throw failure } }
  finally { if (alive && ticket === generation && refreshTicket === refreshGeneration) { refreshing.value = false; schedule() } }
}
async function load() {
  const ticket = ++generation; presetsOpen.value = false; refreshGeneration++; clearTimeout(timer); command.pending.value = null; command.error.value = ''; loading.value = true; readable.value = false; refreshing.value = false; snapshot.value = null; base.value = null; editing.value = false; proposal.value = null; reviewOpen.value = false; selected.value = ''; edgeId.value = ''; checked.value = []; model.value = null; inputValues.value = {}; error.value = ''; notice.value = ''
  try { const value = await workflowRuns.get(id.value); if (!alive || ticket !== generation) return; accept(value); await refresh(true) }
  catch (failure) { if (alive && ticket === generation) error.value = userFacingError(failure, '需求计划无法读取，请重新加载。') }
  finally { if (alive && ticket === generation) loading.value = false }
}
function change(next: WorkflowGraph, nextLayout = layout.value) {
  if (!planning.value || locked.value) return
  undo.value.push(clone({ graph: graph.value, layout: layout.value })); if (undo.value.length > 100) undo.value.shift(); redo.value = []; graph.value = next; layout.value = nextLayout
}
function history(back: boolean) { if (locked.value || !planning.value) return; const from = back ? undo : redo, to = back ? redo : undo, item = from.value.pop(); if (item) { to.value.push(clone({ graph: graph.value, layout: layout.value })); graph.value = item.graph; layout.value = item.layout } }
function select(key: string) { if (uploadBusy.value || nodeBusy.value || inspector.value && !inspector.value.canLeave()) return; selected.value = key; edgeId.value = '' }
function add(module: 'free.readonly' | 'free.write' | 'human') { const next = newNode(module); change({ ...graph.value, nodes: [...graph.value.nodes, next] }, { ...layout.value, positions: { ...layout.value.positions, [next.id]: { x: 40, y: graph.value.nodes.length * 150 } } }); select(next.id) }
function addPreset(next: WorkflowGraph, node: WorkflowNode) {
  if (!planning.value || locked.value) return
  change(next, { ...layout.value, positions: { ...layout.value.positions, [node.id]: autoLayout(next)[node.id]! } }); select(node.id); presetsOpen.value = false
}
function patch(value: WorkflowNode) { if (protectedKeys.value.has(value.id)) return; change(replaceReviewSource(graph.value, value, !inputsFrozen.value)) }
function join(key: string) { if (!planning.value || locked.value) return; if (!connecting.value) { connecting.value = key; return }; try { if (protectedKeys.value.has(key)) throw new Error('这个节点已执行或属于保留区域，不能改变它的前置依赖。'); change(connect(graph.value, connecting.value, key)); connecting.value = ''; error.value = '' } catch (failure) { error.value = userFacingError(failure) } }
function remove(key: string) { if (!planning.value || locked.value || !canRemove(key) || !window.confirm('将节点及其连接移出当前计划？已有执行和交付物历史将保留。')) return; try { const next = removeNode(graph.value, key), position = clone(layout.value); delete position.positions[key]; change(next, position); selected.value = '' } catch (failure) { error.value = userFacingError(failure) } }
function removeEdge() { if (!locked.value && planning.value && edge.value && !protectedKeys.value.has(edge.value.to) && window.confirm('删除这条连接？')) { change({ ...graph.value, edges: graph.value.edges.filter(item => item.id !== edgeId.value) }); edgeId.value = '' } }
function setLayout(value: WorkflowLayout) { if (!locked.value) { if (planning.value) change(graph.value, value); else layout.value = value } }
function exportTemplate() {
  if (!base.value || locked.value || proposal.value || inspector.value && !inspector.value.canLeave() || candidates.value && !candidates.value.canLeave()) return
  exportDraft.value = { requirement: id.value, revision: base.value.revision, title: base.value.title, graph: clone(graph.value), layout: clone(layout.value) }
}
async function save() {
  if (!base.value || exportDraft.value || command.locked.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value || !readable.value || proposalReadOnly.value || !proposalReady.value) return
  if (!planSave.value) planSave.value = preparePlanSave({ ...base.value, version: snapshot.value!.execution.version, state: snapshot.value!.execution.state }, graph.value, layout.value, proposal.value)
  const operation = planSave.value
  await command.submit('保存计划', () => savePlan(operation), async value => { accept(value); reviewOpen.value = false; await refresh(true); notice.value = operation.active && operation.changesPlan ? '计划已应用，请选择执行方式继续。' : '计划已保存。' })
}
async function confirm() {
  if (!base.value || locked.value || graphDirty.value || state.value !== 'PLANNING') return
  const body = { requestKey: crypto.randomUUID(), expectedVersion: snapshot.value!.execution.version }, target = id.value, retainedLayout = layoutDirty.value ? clone(layout.value) : null
  await command.submit('确认计划', () => workflowRuns.confirm(target, body), async () => { accept(await workflowRuns.get(target)); if (retainedLayout) layout.value = retainedLayout; await refresh(true); notice.value = '计划已确认，选择执行方式即可开始。' })
}
async function run(mode: WorkflowRunMode, target: string | null = null) {
  if (!executable.value || !control.value) return
  try {
    if (mode !== 'CONTINUOUS' && !target) throw new Error('请先选择一个节点。')
    const body = { requestKey: crypto.randomUUID(), expectedVersion: control.value.version, expectedControlVersion: control.value.controlVersion, mode, targetKey: target,
      inputs: inputsFrozen.value ? null : readValues(graph.value.inputs, inputValues.value), model: clone(model.value), checkpointAttempts: checkpoints.value.map(checkpoint => checkpoint.attemptId) }
    const targetId = id.value
    await command.submit('执行流程', () => workflowRuns.start(targetId, body), async () => { await refresh(true); notice.value = '' })
  } catch (failure) { error.value = userFacingError(failure) }
}
async function pause() { if (!control.value || locked.value) return; const target = id.value, body = { requestKey: crypto.randomUUID(), expectedControlVersion: control.value.controlVersion }; await command.submit('暂停派发', () => workflowRuns.pause(target, body), async () => { await refresh(true) }) }
async function cancel() { if (!base.value || !beforeStart.value || locked.value || !window.confirm('取消这个尚未执行的需求？历史计划将保留。')) return; const target = id.value, body = { requestKey: crypto.randomUUID(), expectedVersion: snapshot.value!.execution.version }; await command.submit('取消需求', () => workflowRuns.cancel(target, body), async () => { await refresh(true) }) }
function canRemove(key: string) {
  if (beforeStart.value) return true
  if (!base.value || statuses.value[key] === 'ACTIVE' || checkpoints.value.some(check => check.nodeKey === key)) return false
  const retained = (snapshot.value?.execution.nodes || []).filter(run => run.nodeKey !== key && graph.value.nodes.some(node => node.id === run.nodeKey))
  return !protectedNodes(base.value.graph, retained, proposal.value).has(key)
}
async function beginEdit() {
  if (locked.value || terminal.value || ending.value || beforeStart.value || editing.value || inspector.value && !inspector.value.canLeave()) return
  if (control.value?.configured && ['ACTIVE', 'WAITING'].includes(control.value.state)) {
    const target = id.value, body = { requestKey: crypto.randomUUID(), expectedControlVersion: control.value.controlVersion }
    await command.submit('暂停派发并调整计划', () => workflowRuns.pause(target, body), async () => { await refresh(true); editing.value = true })
  } else { await refresh(); if (readable.value && !terminal.value) editing.value = true }
}
function review(value: WorkflowCandidate) {
  if (locked.value || !base.value || !canLeave()) return
  proposal.value = clone(value); graph.value = clone(value.graph); layout.value = clone(base.value.layout)
  layout.value.positions = { ...autoLayout(value.graph), ...layout.value.positions }
  for (const key of Object.keys(layout.value.positions)) if (!graph.value.nodes.some(node => node.id === key)) delete layout.value.positions[key]
  editing.value = true; reviewOpen.value = false; selected.value = ''; edgeId.value = ''; undo.value = []; redo.value = []
}
function discard() {
  if (locked.value || !base.value || dirty.value && !window.confirm('放弃当前画布调整，回到已经生效的计划？候选记录仍然保留。')) return
  accept(base.value); selected.value = ''; edgeId.value = ''; connecting.value = ''; notice.value = ''
}
function canLeave() { if (publicationPanel.value && !publicationPanel.value.canLeave()) return false; if (exportPanel.value && !exportPanel.value.canLeave()) return false; if (uploadBusy.value) return false; if (finishPanel.value && !finishPanel.value.canLeave()) return false; if (candidates.value && !candidates.value.canLeave()) return false; if (inspector.value && !inspector.value.canLeave()) return false; return !(dirty.value || command.pending.value) || window.confirm('当前计划有未保存或待确认的操作，仍要离开？') }
async function reload() { if (canLeave()) { exportDraft.value = null; command.pending.value = null; command.error.value = ''; planSave.value = null; await load() } }
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
const unload = (event: BeforeUnloadEvent) => { if (exportDraft.value || dirty.value || command.pending.value || uploadBusy.value || nodeBusy.value || candidateBusy.value || finishBusy.value || publicationBusy.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { alive = false; generation++; clearTimeout(timer); window.removeEventListener('beforeunload', unload) })
watch(id, () => { exportDraft.value = null; void load() }, { immediate: true })
</script>
<template><main id="main-content" class="workflow-editor workflow-page workflow-requirement"><header class="workflow-editor-header"><RouterLink to="/requirements" class="workflow-back"><Icon icon="lucide:arrow-left" />需求任务</RouterLink><div><p class="eyebrow">{{ proposal ? '候选计划预览' : planning ? '流程规划' : '任务执行' }}</p><h1>{{ base?.title || '读取需求' }}</h1></div><StatusBadge v-if="state" :status="state === 'COMPLETED' ? 'SUCCEEDED' : state" :label="workflowStateLabel(state)" /><span class="workflow-save-state">{{ dirty ? '有未保存修改' : base ? `计划版本 ${base.revision}` : '' }}</span><div class="workflow-inline"><button :disabled="locked || !!proposal" @click="exportTemplate">另存为流程模板</button><button v-if="dirty" :disabled="!!exportDraft || command.locked.value || nodeBusy || candidateBusy || !readable || !proposalReady" @click="save">{{ planSave ? '重试保存计划' : proposal ? '确认并应用候选计划' : editing ? '应用计划调整' : planning ? '保存计划' : '保存布局' }}</button><button v-if="editing || proposal" :disabled="locked" @click="discard">{{ proposalReadOnly ? '退出候选预览' : '放弃调整' }}</button><button v-else-if="!beforeStart && !terminal && !ending" :disabled="locked" @click="beginEdit">调整后续计划</button><button v-if="!beforeStart" :disabled="locked || editing || !!proposal" @click="reviewOpen = !reviewOpen">候选计划</button><button v-if="state === 'PLANNING'" class="primary-button" :disabled="locked || graphDirty" @click="confirm">确认计划</button><button v-if="control?.configured && ['ACTIVE', 'WAITING'].includes(control.state)" :disabled="locked" @click="pause">暂停后续派发</button><button v-if="control?.configured && control.state !== 'ACTIVE' && control.state !== 'DONE' && !ending && control.mode" :disabled="!executable || !!control.targetKey && !graph.nodes.some(node => node.id === control?.targetKey)" @click="run(control.mode, control.targetKey)">继续原执行范围</button><button v-if="state !== 'PLANNING' && !terminal && !ending" class="primary-button" :disabled="!executable" @click="run('CONTINUOUS')">连续执行</button></div></header>
  <div v-if="error || command.error.value" role="alert" class="workflow-error">{{ error || command.error.value }}<button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新操作结果' : '重试原操作' }}</button><button v-else @click="reload">重新加载</button></div><p v-if="notice" class="workflow-notice" role="status">{{ notice }}</p><p v-if="loading" role="status">正在读取需求计划…</p>
  <WorkflowSaveTemplate v-if="exportDraft" ref="exportPanel" v-bind="exportDraft" @close="exportDraft = null" />
  <WorkflowPublication v-if="base && !beforeStart && !loading" :key="id" ref="publicationPanel" @busy="value => publicationBusy = value" :requirement="id" :revision="base.revision" :disabled="!!exportDraft || command.locked.value || !readable || dirty || editing || !!proposal" />
  <WorkflowFinish v-if="base && snapshot && !loading" :key="id" ref="finishPanel" :requirement="id" :version="snapshot.execution.version" :state="snapshot.execution.state" :disabled="!!exportDraft || command.locked.value || nodeBusy || candidateBusy || !readable || dirty || editing || !!proposal" :before-open="() => !inspector || inspector.canLeave()" @busy="value => finishBusy = value" @changed="refresh()" />
  <section v-if="proposal" class="workflow-proposal-banner" aria-label="候选计划预览"><div><strong>{{ proposal.sourceTitle }} 提出的计划 · {{ proposalReadOnly ? '只读预览' : '尚未生效' }}</strong><p>新增 {{ proposal.changes.added.length }} 个节点，修改 {{ proposal.changes.changed.length }} 个节点，移除 {{ proposal.changes.removed.length }} 个节点。</p><WorkflowPlanDiff :before="proposal.originalGraph" :after="graph" /><p v-if="!proposalReady">来源节点尚未成功收尾，可以先查看和修改后续方案。</p><p v-if="proposalReadOnly">这里展示原始候选，当前生效计划保持原样。</p></div></section>
  <WorkflowCandidates v-if="reviewOpen && base" :key="id" ref="candidates" :requirement="id" :disabled="!!exportDraft || command.locked.value || nodeBusy || finishBusy || publicationBusy || !readable" @review="review" @changed="refresh()" @busy="value => candidateBusy = value" @close="reviewOpen = false" />
  <section v-if="base && !loading" class="workflow-run-controls"><div><strong>{{ terminal ? workflowStateLabel(state) : control?.configured ? workflowStateLabel(control.state) : '安排本次执行' }}</strong><p v-if="!terminal">{{ workflowReasonLabel(control?.reasonCode) }}</p></div><div v-if="state !== 'PLANNING' && !terminal && !ending" class="workflow-inline"><button :disabled="!executable || !selected" @click="run('SINGLE', selected)">{{ summary?.state === 'FAILED' ? '重试所选节点' : '执行所选节点' }}</button><button :disabled="!executable || !selected" @click="run('UNTIL', selected)">运行至所选节点</button></div><div v-if="checkpoints.length" class="workflow-checkpoints"><strong>待检查的交付物</strong><label v-for="check in checkpoints" :key="check.attemptId"><input v-model="checked" type="checkbox" :value="check.attemptId" :disabled="locked" /><span>确认 {{ graph.nodes.find(item => item.id === check.nodeKey)?.title || '已完成节点' }} 的结果</span><button @click="select(check.nodeKey)">查看节点</button></label></div></section>
  <WorkflowPresetPicker v-if="presetsOpen && planning" :graph="graph" :disabled="locked" @insert="addPreset" @close="presetsOpen = false" />
  <div v-if="base && !loading" class="workflow-studio"><aside class="workflow-module-rail"><template v-if="planning"><h2>添加节点</h2><button :disabled="locked" :aria-expanded="presetsOpen" @click="presetsOpen = !presetsOpen">预设工作模块</button><button :disabled="locked" @click="add('free.readonly')">只读分析</button><button :disabled="locked" @click="add('free.write')">文件工作</button><button :disabled="locked" @click="add('human')">人工检查</button></template><template v-else><h2>执行节点</h2><button v-for="item in graph.nodes" :key="item.id" :aria-pressed="selected === item.id" :disabled="nodeBusy" @click="select(item.id)"><strong>{{ item.title }}</strong><small>{{ workflowStateLabel(statuses[item.id] || 'PENDING') }}</small></button></template><WorkflowModelChoice v-if="graph.nodes.some(item => item.kind === 'WORK')" v-model="model" :disabled="locked" /><button @click="select('')">需求与资料</button><button v-if="beforeStart" class="danger" :disabled="locked" @click="cancel">取消需求</button></aside>
    <section class="workflow-center"><div class="workflow-toolbar"><template v-if="planning"><button aria-label="撤销计划修改" :disabled="locked || !undo.length" @click="history(true)"><Icon icon="lucide:undo-2" /></button><button aria-label="重做计划修改" :disabled="locked || !redo.length" @click="history(false)"><Icon icon="lucide:redo-2" /></button></template><span>{{ graph.nodes.length }} 个节点</span><button :disabled="locked" @click="setLayout({ ...layout, positions: autoLayout(graph) })">自动排列</button><button :disabled="refreshing || command.locked.value" @click="refresh()">刷新状态</button></div><WorkflowCanvas :graph="graph" :layout="layout" :selected="selected" :connecting="connecting" :readonly="!planning || locked" :movable="!locked" :states="statuses" :role-names="roleNames" @select="select" @connect="join" @edge="key => { if (planning && !locked) { edgeId = key; selected = '' } }" @layout="setLayout" @remove="remove" @cancel="connecting = ''" /></section>
    <WorkflowNodeEditor v-if="(planning || proposal) && node" :node="node" :graph="graph" :disabled="locked || proposalReadOnly || protectedKeys.has(node.id)" :remove-disabled="locked || proposalReadOnly || !canRemove(node.id)" :readonly-reason="proposalReadOnly ? '历史候选仅供查看。' : protectedKeys.has(node.id) ? '此节点及其输入已固定，可以调整它之后尚未执行的工作。' : undefined" @change="patch" @remove="remove(node.id)" @role-label="(key, title) => roleNames[key] = title" />
    <WorkflowNodeRun v-else-if="node" :key="`${id}-${node.id}`" ref="inspector" :requirement="id" :version="snapshot?.execution.version ?? base.version" :node="node" :summary="summary" :locked="!!exportDraft || command.locked.value || finishBusy || publicationBusy || !readable || ending && node.kind === 'HUMAN'" @busy="value => nodeBusy = value" @changed="refresh()" />
    <aside v-else-if="planning && edge" class="workflow-inspector"><h2>连接设置</h2><fieldset class="workflow-fields" :disabled="locked || protectedKeys.has(edge.to)"><label>执行条件<select :value="edge.outcome || ''" @change="change({ ...graph, edges: graph.edges.map(item => item.id === edge?.id ? { ...item, outcome: ($event.target as HTMLSelectElement).value || null } : item) })"><option value="">前置节点成功完成</option><option v-for="value in graph.nodes.find(item => item.id === edge?.from)?.outcomes" :key="value" :value="value">{{ outcomeTitle(graph.nodes.find(item => item.id === edge?.from), value) }}</option></select></label><button class="danger" @click="removeEdge">删除连接</button></fieldset></aside>
    <aside v-else class="workflow-inspector" aria-label="需求与资料"><h2>需求与资料</h2><p class="workflow-objective">{{ base.objective }}</p><p v-if="!graph.inputs.length">此流程未声明额外公共资料。</p><p v-else-if="inputsFrozen">本次资料已固定，可在节点详情查看实际输入。</p><WorkflowValueFields v-else :date-fields="historyDateFields" :repository-context="{ project: base.projectId, fields: repositoryFields }" :upload-context="{ requirement: id, version: snapshot?.execution.version ?? base.version, revision: base.revision }" :fields="graph.inputs" :values="inputValues" @busy="value => uploadBusy = value" :disabled="locked" @change="value => inputValues = value" /><details v-if="beforeStart && !inputsFrozen"><summary>调整公共资料设置</summary><WorkflowPublicInputs :graph="graph" :disabled="locked" @change="value => change(value)" /></details><p v-if="state === 'PLANNING'" class="workflow-inspector-hint">确认计划后选择执行方式。确认本身不会创建模型会话或占用工作目录。</p></aside>
  </div></main></template>
