<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import WorkflowContextPanel from '@/components/workflow/WorkflowContextPanel.vue'
import WorkflowNodeList from '@/components/workflow/WorkflowNodeList.vue'
import WorkflowAddMenu from '@/components/workflow/WorkflowAddMenu.vue'
import WorkflowCanvas from '@/components/workflow/WorkflowCanvas.vue'
import WorkflowPresetPicker from '@/components/workflow/WorkflowPresetPicker.vue'
import { replaceReviewSource } from '@/components/workflow/reviewSource'
import WorkflowNodeEditor from '@/components/workflow/WorkflowNodeEditor.vue'
import WorkflowPublicInputs from '@/components/workflow/WorkflowPublicInputs.vue'
import { autoLayout, clone, connect, emptyGraph, emptyLayout, newNode, removeNode, outcomeTitle } from '@/components/workflow/graph'
import { prepareCopy, prepareSave, saveDraft, type WorkflowDraft, type WorkflowSave } from '@/components/workflow/save'
import { userFacingError } from '@/utils/displayLabels'
import type { WorkflowGraph, WorkflowDiagnostic, WorkflowLayout, WorkflowNode, WorkflowTemplate } from '@/types/domain'
import '@/components/workflow/workflow.css'
import '@/components/workflow/studio.css'

const presetsOpen = ref(false)
const route = useRoute(), router = useRouter()
const fresh = (): WorkflowDraft => ({ title: '未命名流程', description: '', graph: emptyGraph(), layout: emptyLayout() })
const draft = ref(fresh()), base = ref<WorkflowTemplate | null>(null), baseline = ref(JSON.stringify(draft.value))
const ready = ref(false), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), selected = ref(''), selectedEdge = ref(''), connecting = ref('')
const pending = ref<WorkflowSave | null>(null), conflict = ref(false), diagnostics = ref<WorkflowDiagnostic[]>([])
const undo = ref<WorkflowDraft[]>([]), redo = ref<WorkflowDraft[]>([]), roleNames = ref<Record<string, string>>({})
const canvas = ref<InstanceType<typeof WorkflowCanvas>>(), inspecting = ref<'none' | 'flow' | 'node' | 'edge' | 'add' | 'tools' | 'nodes'>('none')
const builtin = computed(() => base.value?.builtin ?? false), locked = computed(() => !ready.value || loading.value || busy.value || !!pending.value || builtin.value)
const dirty = computed(() => !!pending.value || !builtin.value && JSON.stringify(draft.value) !== baseline.value)
const node = computed(() => draft.value.graph.nodes.find(n => n.id === selected.value))
const edge = computed(() => draft.value.graph.edges.find(e => e.id === selectedEdge.value))
const parent = computed(() => draft.value.graph.nodes.find(n => n.id === edge.value?.from))
let generation = 0, navigatingAfterSave = false
function accept(value: WorkflowTemplate) {
  base.value = value; draft.value = clone({ title: value.title, description: value.description, graph: value.graph, layout: value.layout }); baseline.value = JSON.stringify(draft.value)
  diagnostics.value = value.diagnostics; pending.value = null; conflict.value = false; undo.value = []; redo.value = []
}
async function load() {
  const ticket = ++generation; inspecting.value = 'none'; presetsOpen.value = false; busy.value = false; ready.value = false; loading.value = true; error.value = ''; notice.value = ''; selected.value = ''; selectedEdge.value = ''; connecting.value = ''
  try {
    const id = typeof route.params.id === 'string' ? route.params.id : null
    if (id) { const value = await workflowApi.get(id); if (ticket === generation) accept(value) }
    else { base.value = null; draft.value = fresh(); baseline.value = JSON.stringify(draft.value); diagnostics.value = []; pending.value = null; undo.value = []; redo.value = [] }
    if (ticket === generation) ready.value = true
  } catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '流程无法读取，请重新加载。') }
  finally { if (ticket === generation) loading.value = false }
}
function change(value: WorkflowDraft) {
  if (locked.value) return
  undo.value.push(clone(draft.value)); if (undo.value.length > 100) undo.value.shift()
  redo.value = []; draft.value = value; diagnostics.value = []; notice.value = ''
}
function history(back: boolean) {
  if (locked.value) return
  const source = back ? undo : redo, destination = back ? redo : undo, value = source.value.pop()
  if (value) { destination.value.push(clone(draft.value)); draft.value = value; diagnostics.value = [] }
}
function add(module: 'free.readonly' | 'free.write' | 'human') {
  if (locked.value) return
  const next = newNode(module), graph = { ...draft.value.graph, nodes: [...draft.value.graph.nodes, next] }
  change({ ...draft.value, graph, layout: { ...draft.value.layout, positions: { ...draft.value.layout.positions, [next.id]: { x: (graph.nodes.length - 1) * 40, y: (graph.nodes.length - 1) * 150 } } } })
  locate(next.id)
}
function addPreset(graph: WorkflowGraph, node: WorkflowNode) {
  if (locked.value) return
  change({ ...draft.value, graph, layout: { ...draft.value.layout, positions: { ...draft.value.layout.positions, [node.id]: autoLayout(graph)[node.id]! } } }); locate(node.id); presetsOpen.value = false
}
function select(id: string) { selected.value = id; inspecting.value = 'node'; selectedEdge.value = ''; presetsOpen.value = false }
function selectEdge(id: string) { selectedEdge.value = id; selected.value = ''; connecting.value = ''; inspecting.value = 'edge'; presetsOpen.value = false }
function dismiss() {
  const previous = selected.value
  selected.value = ''; selectedEdge.value = ''; connecting.value = ''; inspecting.value = 'none'; presetsOpen.value = false
  void nextTick(() => canvas.value?.focus(previous))
}
function toggle(panel: 'flow' | 'add' | 'tools' | 'nodes') {
  const next = inspecting.value === panel ? 'none' : panel
  selected.value = ''; selectedEdge.value = ''; connecting.value = ''; presetsOpen.value = false; inspecting.value = next
}
function locate(key: string) { select(key); if (selected.value === key) void nextTick(() => { canvas.value?.reveal(key); canvas.value?.focus(key) }) }
function openPresets() { inspecting.value = 'none'; presetsOpen.value = true }
watch(() => draft.value.graph, graph => {
  if (selected.value && !graph.nodes.some(item => item.id === selected.value) || selectedEdge.value && !graph.edges.some(item => item.id === selectedEdge.value)) dismiss()
  if (connecting.value && !graph.nodes.some(item => item.id === connecting.value)) connecting.value = ''
})
function join(id: string) {
  if (locked.value) return
  if (!connecting.value) { connecting.value = id; return }
  try { change({ ...draft.value, graph: connect(draft.value.graph, connecting.value, id) }); connecting.value = ''; error.value = '' }
  catch (failure) { error.value = userFacingError(failure, '节点无法连接，请检查依赖顺序。') }
}
function joinPair(from: string, to: string) {
  if (locked.value) return
  try { change({ ...draft.value, graph: connect(draft.value.graph, from, to) }); connecting.value = ''; error.value = '' }
  catch (failure) { error.value = userFacingError(failure, '节点无法连接，请检查依赖顺序。') }
}
function remove(id: string) {
  if (locked.value || !window.confirm('删除这个节点及其连接？已绑定它的输入需要先调整。')) return
  try { const graph = removeNode(draft.value.graph, id), layout = clone(draft.value.layout); delete layout.positions[id]; change({ ...draft.value, graph, layout }); dismiss(); error.value = '' }
  catch (failure) { error.value = userFacingError(failure, '节点仍被使用，请先调整输入。') }
}
function patchNode(value: WorkflowNode) { change({ ...draft.value, graph: replaceReviewSource(draft.value.graph, value) }) }
function layout(value: WorkflowLayout) { if (builtin.value) draft.value.layout = value; else change({ ...draft.value, layout: value }) }
function removeEdge() {
  if (locked.value || !edge.value || !window.confirm('删除这条连接？请确认依赖它的输入仍有有效来源。')) return
  change({ ...draft.value, graph: { ...draft.value.graph, edges: draft.value.graph.edges.filter(e => e.id !== selectedEdge.value) } }); dismiss()
}
async function validate() {
  const ticket = generation
  busy.value = true; error.value = ''
  try { const result = await workflowApi.validate(draft.value.graph); if (ticket === generation) { diagnostics.value = result; notice.value = result.length ? '' : '流程检查通过。' } }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '流程检查失败，请重试。') }
  finally { if (ticket === generation) busy.value = false }
}
async function save(asNew = false) {
  if (busy.value || !ready.value) return
  if (asNew && !window.confirm('将当前画布保存为新流程？原流程与历史版本会保留。')) return
  if (asNew || !pending.value) pending.value = builtin.value && base.value ? prepareCopy(base.value) : prepareSave(draft.value, asNew ? null : base.value)
  const ticket = generation
  busy.value = true; error.value = ''; conflict.value = false
  try {
    const value = await saveDraft(pending.value); if (ticket !== generation) return; accept(value); notice.value = '流程已保存。'
    if (route.params.id !== value.id) { navigatingAfterSave = true; await router.replace(`/workflows/${value.id}`); navigatingAfterSave = false }
  } catch (failure) {
    if (ticket !== generation) return
    error.value = userFacingError(failure, '保存结果暂时无法确认，请重试保存。')
    conflict.value = failure instanceof ApiError && failure.status === 409
    if (failure instanceof ApiError && failure.status === 400 && !pending.value?.graphReceipt) pending.value = null
  } finally { if (ticket === generation) busy.value = false }
}
function unsettledSave() {
  if (busy.value) { error.value = '操作仍在处理中，请等待结果后再离开。'; return true }
  if (pending.value) { error.value = '保存结果仍待确认，请重试原保存操作，确认结果后再离开。'; return true }
  return false
}
async function reload() { if (!unsettledSave() && (!dirty.value || window.confirm('放弃当前草稿并读取最新流程？之前已成功保存的内容会保留。'))) await load() }
const canLeave = () => navigatingAfterSave || !unsettledSave() && (!dirty.value || window.confirm('当前画布有未保存的修改，仍要离开？'))
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
const beforeUnload = (event: BeforeUnloadEvent) => { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { generation++; window.removeEventListener('beforeunload', beforeUnload) })
watch(() => route.params.id, () => { if (!navigatingAfterSave) void load() }, { immediate: true })
</script>
<template>
  <main id="main-content" class="workflow-editor workflow-page" @keydown.esc="dismiss">
    <header class="workflow-editor-header">
      <RouterLink to="/workflows" class="workflow-back" aria-label="返回流程库"><Icon icon="lucide:arrow-left" /></RouterLink>
      <div class="workflow-title"><p class="eyebrow">{{ builtin ? '内置流程' : '流程创作' }}</p><h1>{{ draft.title }}</h1></div>
      <span class="workflow-save-state" :class="{ 'is-dirty': dirty }">{{ builtin ? '只读 · 复制后可修改' : dirty ? '未保存' : base ? `版本 ${base.revision}` : '新流程' }}</span>
      <button v-if="builtin" class="primary-button" :disabled="busy || loading || !ready" @click="save(!pending)">{{ pending ? '重试复制' : '复制为自定义流程' }}</button>
      <button v-else class="primary-button" :disabled="busy || loading || !ready" @click="save()"><Icon icon="lucide:check" />{{ busy ? '处理中…' : pending ? '重试保存' : '保存流程' }}</button>
    </header>
    <div v-if="error" class="workflow-error" role="alert">{{ error }}<button :disabled="busy" @click="reload">重新加载</button><button v-if="conflict" :disabled="busy" @click="save(true)">草稿另存为新流程</button></div>
    <p v-if="notice" class="workflow-notice" role="status">{{ notice }}</p>
    <p v-if="loading" role="status">正在读取流程…</p>
    <div v-if="ready" class="workflow-studio">
      <div class="workflow-toolbar" aria-label="画布工具">
        <button :disabled="locked" :aria-expanded="inspecting === 'add'" @click="toggle('add')"><Icon icon="lucide:plus" />添加节点</button>
        <span class="workflow-tool-divider" />
        <button :aria-expanded="inspecting === 'flow'" @click="toggle('flow')"><Icon icon="lucide:sliders-horizontal" />流程设置</button>
        <template v-if="undo.length || redo.length"><span class="workflow-tool-divider" /><button :disabled="locked || !undo.length" aria-label="撤销修改" title="撤销修改" @click="history(true)"><Icon icon="lucide:undo-2" /></button><button :disabled="locked || !redo.length" aria-label="重做修改" title="重做修改" @click="history(false)"><Icon icon="lucide:redo-2" /></button></template>
        <button aria-label="更多工具" title="更多工具" :aria-expanded="inspecting === 'tools'" @click="toggle('tools')"><Icon icon="lucide:ellipsis" /></button>
      </div>
      <WorkflowCanvas ref="canvas" :graph="draft.graph" :layout="draft.layout" :selected="selected" :selected-edge="selectedEdge" :readonly="locked" :connecting="connecting" :role-names="roleNames" @select="select" @edge="selectEdge" @connect="join" @connect-pair="joinPair" @layout="layout" @remove="remove" @cancel="dismiss" />
      <WorkflowContextPanel v-if="inspecting === 'add'" title="添加节点" class="workflow-tools-panel" @close="dismiss"><WorkflowAddMenu :disabled="locked" @add="add" @presets="openPresets" /></WorkflowContextPanel>
      <WorkflowContextPanel v-else-if="inspecting === 'tools'" title="更多工具" class="workflow-tools-panel" @close="dismiss"><div class="workflow-tool-actions"><button @click="toggle('nodes')"><Icon icon="lucide:list-tree" />节点列表</button><button :disabled="busy || loading || !ready" @click="validate"><Icon icon="lucide:list-checks" />检查流程</button><button :disabled="locked" @click="layout({ ...draft.layout, positions: autoLayout(draft.graph) })"><Icon icon="lucide:network" />自动排列</button><p>拖动空白处平移，Ctrl + 滚轮缩放。选中节点后可用方向键移动，Delete 删除，Esc 取消选择。</p></div></WorkflowContextPanel>
    <WorkflowContextPanel v-if="inspecting === 'nodes'" title="节点列表" class="workflow-tools-panel" @close="dismiss"><WorkflowNodeList :nodes="draft.graph.nodes" @select="locate" /></WorkflowContextPanel>
      <WorkflowContextPanel v-if="presetsOpen" title="预设工作模块" class="workflow-preset-panel" @close="dismiss"><WorkflowPresetPicker :graph="draft.graph" :disabled="locked" @insert="addPreset" @close="dismiss" /></WorkflowContextPanel>
      <WorkflowContextPanel v-else-if="inspecting === 'node' && node" :key="node.id" title="节点详情" :focus-on-open="false" @close="dismiss"><WorkflowNodeEditor :node="node" :graph="draft.graph" :disabled="locked" @change="patchNode" @remove="remove(node.id)" @role-label="(id, label) => roleNames[id] = label" /></WorkflowContextPanel>
      <WorkflowContextPanel v-else-if="inspecting === 'edge' && edge" title="连接设置" @close="dismiss"><aside class="workflow-inspector" aria-label="连接设置"><p class="workflow-edge-route">{{ parent?.title }} <Icon icon="lucide:arrow-right" /> {{ draft.graph.nodes.find(n => n.id === edge?.to)?.title }}</p><fieldset :disabled="locked" class="workflow-fields"><label>执行条件<select :value="edge.outcome || ''" @change="change({ ...draft, graph: { ...draft.graph, edges: draft.graph.edges.map(e => e.id === edge?.id ? { ...e, outcome: ($event.target as HTMLSelectElement).value || null } : e) } })"><option value="">前置节点成功完成</option><option v-for="outcome in parent?.outcomes" :key="outcome" :value="outcome">{{ outcomeTitle(parent, outcome) }}</option></select></label><p>条件依据前置节点交付的业务结果。</p><button class="danger" @click="removeEdge">删除连接</button></fieldset></aside></WorkflowContextPanel>
      <WorkflowContextPanel v-else-if="inspecting === 'flow'" title="流程设置" @close="dismiss"><aside class="workflow-inspector" aria-label="流程设置"><fieldset :disabled="locked" class="workflow-fields"><label>流程名称<input :value="draft.title" maxlength="120" @input="change({ ...draft, title: ($event.target as HTMLInputElement).value })" /></label><label>流程说明<textarea :value="draft.description" rows="3" @input="change({ ...draft, description: ($event.target as HTMLTextAreaElement).value })" /></label></fieldset><WorkflowPublicInputs :graph="draft.graph" :disabled="locked" @change="graph => change({ ...draft, graph })" /></aside></WorkflowContextPanel>
    </div>
    <details v-if="diagnostics.length" class="workflow-diagnostics" open><summary>{{ diagnostics.length }} 项需要处理</summary><ul><li v-for="(item, index) in diagnostics" :key="index">{{ userFacingError(item.message, '节点配置需要补充，请检查角色、输入和完成标准。') }}</li></ul></details>
  </main>
</template>
