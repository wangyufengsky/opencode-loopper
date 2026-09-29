<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError } from '@/utils/displayLabels'
import type { WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowTemplateMode, WorkflowTemplatePreview } from '@/types/domain'
import WorkflowCanvas from './WorkflowCanvas.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import WorkflowPlanDiff from './WorkflowPlanDiff.vue'
import { autoLayout, clone } from './graph'
import { useWorkflowCommand } from './command'

const props = defineProps<{ requirement: string; revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout }>()
const emit = defineEmits<{ close: [] }>()
// The parent passes an immutable canvas snapshot, including any unsaved local plan edits.
const mode = ref<WorkflowTemplateMode>('CURRENT'), title = ref(`${props.title} · 流程`.slice(0, 120)), description = ref('')
const preview = ref<WorkflowTemplatePreview | null>(null), initialAvailable = ref(true), reading = ref(false), error = ref(''), selected = ref(''), saved = ref<WorkflowReceipt | null>(null)
const command = useWorkflowCommand(), roleNames = ref<Record<string, string>>({})
let alive = true, ticket = 0
const selection = () => ({ expectedRevision: props.revision, mode: mode.value, graph: clone(props.graph), layout: clone(props.layout) })
const node = computed(() => preview.value?.graph.nodes.find(value => value.id === selected.value))
const layout = computed(() => preview.value ? { ...preview.value.layout, positions: { ...autoLayout(preview.value.graph), ...preview.value.layout.positions } } : props.layout)
async function read() {
  if (command.locked.value || saved.value) return
  const current = ++ticket; reading.value = true; preview.value = null; selected.value = ''; error.value = ''; command.error.value = ''
  try { const value = await workflowRuns.previewTemplate(props.requirement, selection()); if (alive && current === ticket) { preview.value = value; initialAvailable.value = value.initialAvailable } }
  catch (failure) { if (alive && current === ticket) error.value = userFacingError(failure, '暂时无法预览，请重试。') }
  finally { if (alive && current === ticket) reading.value = false }
}
async function save() {
  if (!preview.value || reading.value || !title.value.trim() || command.locked.value || saved.value) return
  const body = { requestKey: crypto.randomUUID(), title: title.value.trim(), description: description.value.trim(), selection: selection(), previewSha256: preview.value.sha256 }
  await command.submit('另存为流程模板', () => workflowRuns.saveTemplate(props.requirement, body), async receipt => { saved.value = receipt })
  if (command.error.value && !command.pending.value) preview.value = null
}
function canLeave() { return !command.locked.value }
function close() { if (canLeave()) emit('close') }
watch(mode, read, { immediate: true })
onBeforeUnmount(() => { alive = false; ticket++ })
defineExpose({ canLeave })
</script>
<template>
  <section class="workflow-save-template" role="dialog" aria-modal="false" aria-label="另存为流程模板">
    <header><div><h2>另存为流程模板</h2><p>保留角色、任务和输入设置，新任务重新提供资料。</p></div><button :disabled="command.locked.value" @click="close">返回任务画布</button></header>
    <div v-if="saved" role="status" class="workflow-notice">已保存为自定义流程。<RouterLink :to="`/workflows/${encodeURIComponent(saved.id)}`">打开新流程</RouterLink><p>原任务及画布中的未保存修改仍然保留。</p></div>
    <div v-else>
      <fieldset :disabled="command.locked.value" class="workflow-fields">
        <label>模板名称<input v-model="title" required maxlength="120" /></label>
        <label>模板说明<textarea v-model="description" maxlength="4000" rows="2" /></label>
        <label>保存结构<select v-model="mode"><option value="CURRENT">保留当前步骤（默认）</option><option value="INITIAL" :disabled="!initialAvailable">使用首次执行时的结构</option></select></label>
      </fieldset>
      <p v-if="mode === 'CURRENT'">保存此刻画布上的步骤，包括未保存的调整。节点中的具体路径、章节和任务说明会保留，可在新模板中继续修改。</p>
      <p v-else>使用本任务第一次开始节点执行时的计划版本，保留原有动态分批能力；之后新增或修改的节点不纳入本次模板。</p>
      <p v-if="!initialAvailable">任务尚未执行，目前可保存当前步骤。</p>
      <p v-if="reading" role="status">正在生成模板预览…</p>
      <template v-if="preview">
        <p v-if="preview.fixedPlanningNodes.length" class="workflow-notice">已展开的程序规划将改为人工确认固定步骤，避免下次重复分批：{{ preview.fixedPlanningNodes.join('、') }}。</p>
        <WorkflowPlanDiff :before="graph" :after="preview.graph" />
        <div class="workflow-template-preview"><WorkflowCanvas :graph="preview.graph" :layout="layout" :selected="selected" :readonly="true" :role-names="roleNames" @select="value => selected = value" /><WorkflowNodeEditor v-if="node" :node="node" :graph="preview.graph" :disabled="true" :remove-disabled="true" readonly-reason="这是将要保存的模板。保存后可打开新流程继续编辑。" @role-label="(key, value) => roleNames[key] = value" /></div>
        <p v-if="preview.graph.inputs.length">新任务需要提供：{{ preview.graph.inputs.map(value => value.title).join('、') }}。</p>
        <details v-if="preview.diagnostics.length"><summary>模板还有 {{ preview.diagnostics.length }} 项配置待补充</summary><ul><li v-for="(item, index) in preview.diagnostics" :key="index">{{ item.message }}</li></ul></details>
      </template>
      <div v-if="error || command.error.value" role="alert" class="workflow-error">{{ error || command.error.value }}<button v-if="command.pending.value" type="button" :disabled="command.busy.value" @click="command.retry">重试原保存操作</button><button v-else type="button" :disabled="reading" @click="read">重新预览</button></div>
      <button class="primary-button" type="button" @click="save" :disabled="!preview || reading || command.locked.value || !title.trim()">{{ command.busy.value ? '正在保存…' : '确认保存为新流程' }}</button>
    </div>
  </section>
</template>
<style scoped>
.workflow-save-template { padding: 20px; margin-bottom: 18px; background: var(--color-bg-surface); border: 1px solid var(--color-border-default); border-radius: var(--radius-control); }
.workflow-save-template header { display: flex; flex-wrap: wrap; align-items: start; justify-content: space-between; gap: 12px; }
.workflow-save-template h2 { margin: 0; }.workflow-save-template p { line-height: 1.6; color: var(--color-text-secondary); overflow-wrap: anywhere; }
.workflow-save-template fieldset { border: 0; padding: 0; max-width: 720px; }
.workflow-template-preview { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, 340px); height: 430px; margin: 14px 0; overflow: hidden; border: 1px solid var(--color-border-default); }
.workflow-template-preview:has(> :only-child) { grid-template-columns: minmax(0, 1fr); }
@media (max-width: 760px) { .workflow-template-preview { display: flex; flex-direction: column; height: auto; }.workflow-template-preview :deep(.workflow-canvas) { min-height: 300px; }.workflow-template-preview :deep(.workflow-inspector) { max-height: 400px; width: 100%; } }
</style>
