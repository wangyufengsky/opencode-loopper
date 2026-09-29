<script setup lang="ts">
import WorkflowVerificationReport from './WorkflowVerificationReport.vue'
import WorkflowCommandReport from './WorkflowCommandReport.vue'
import WorkflowNativeTestReport from './WorkflowNativeTestReport.vue'
import WorkflowTestSummaryReport from './WorkflowTestSummaryReport.vue'
import WorkflowSourceReport from './WorkflowSourceReport.vue'
import WorkflowHistoryAnalysisReport from './WorkflowHistoryAnalysisReport.vue'
import WorkflowSnapshotReport from './WorkflowSnapshotReport.vue'
import WorkflowSnapshotSummary from './WorkflowSnapshotSummary.vue'
import WorkflowSnapshotPartialReport from './WorkflowSnapshotPartialReport.vue'
import WorkflowHistorySummary from './WorkflowHistorySummary.vue'
import WorkflowReviewSourceReport from './WorkflowReviewSourceReport.vue'
import WorkflowHistoryReport from './WorkflowHistoryReport.vue'
import WorkflowRepositoryReport from './WorkflowRepositoryReport.vue'
import WorkflowDocumentReport from './WorkflowDocumentReport.vue'
import WorkflowDocumentReviewReport from './WorkflowDocumentReviewReport.vue'
import WorkflowSourcePlanReport from './WorkflowSourcePlanReport.vue'
import WorkflowDocumentPlanReport from './WorkflowDocumentPlanReport.vue'
import WorkflowTestProfileReport from './WorkflowTestProfileReport.vue'
import WorkflowSourceDesignReport from './WorkflowSourceDesignReport.vue'
import WorkflowTestReviewReport from './WorkflowTestReviewReport.vue'
import WorkflowTestScopeReport from './WorkflowTestScopeReport.vue'
import WorkflowTestDesignReport from './WorkflowTestDesignReport.vue'
import WorkflowReviewReport from './WorkflowReviewReport.vue'
import WorkflowCommandEvidence from './WorkflowCommandEvidence.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCommandEvidence as CommandEvidence, WorkflowActivity, WorkflowAttempt, WorkflowInputs, WorkflowNode, WorkflowNodeSummary, WorkflowResult } from '@/types/domain'
import { userFacingError, workflowReasonLabel, workflowStateLabel } from '@/utils/displayLabels'
import StatusBadge from '@/components/StatusBadge.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import WorkflowValueFields from './WorkflowValueFields.vue'
import WorkflowFiles from './WorkflowFiles.vue'
import WorkflowKnowledgeEvidence from './WorkflowKnowledgeEvidence.vue'
import WorkflowKnowledgeReport from './WorkflowKnowledgeReport.vue'
import WorkflowInputContent from './WorkflowInputContent.vue'
import { outcomeTitle } from './graph'
import { readValues } from './values'
import { useWorkflowCommand } from './command'
const isDocument = (value: unknown) => !!value && typeof value === 'object' && 'type' in value && ['DESIGN_DOCUMENT', 'ASSESSMENT_DOCUMENT', 'HISTORY_DOCUMENT', 'SNAPSHOT_DOCUMENT'].includes(String(value.type))
const props = defineProps<{ requirement: string; version: number; node: WorkflowNode; summary?: WorkflowNodeSummary; locked?: boolean }>()
const emit = defineEmits<{ changed: []; busy: [value: boolean] }>()
const command = useWorkflowCommand(), history = ref<WorkflowAttempt[]>([]), cursor = ref<string | null>(null), selected = ref(''), metadata = ref<WorkflowAttempt | null>(null), definition = ref<WorkflowNode | null>(null)
const error = ref(''), loading = ref(false), readable = ref(false), input = ref<WorkflowInputs | null>(null), result = ref<WorkflowResult | null>(null), activity = ref<WorkflowActivity | null>(null), open = ref<'input' | 'result' | 'activity' | 'command' | 'knowledge' | 'partial' | null>(null)
const commandEvidence = ref<CommandEvidence | null>(null)
const processState = computed(() => metadata.value?.commandState || metadata.value?.modelState)
const humanSummary = ref(''), outcome = ref(''), values = ref<Record<string, string>>({})
const current = computed(() => selected.value === props.summary?.latestAttemptId), terminal = computed(() => !!metadata.value && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(metadata.value.state))
const locked = computed(() => !!props.locked || command.locked.value || loading.value || !readable.value), human = computed(() => current.value && metadata.value?.state === 'WAITING_INPUT' && definition.value?.kind === 'HUMAN')
let alive = true, generation = 0, refreshGeneration = 0, evidenceGeneration = 0, timer: ReturnType<typeof setTimeout> | undefined
const details = () => definition.value || props.node
async function list(more = false) {
  const ticket = generation
  try { const page = await workflowRuns.attempts(props.requirement, props.node.id, more ? cursor.value || '' : ''); if (alive && ticket === generation) { history.value = more ? [...history.value, ...page.items] : page.items; cursor.value = page.nextCursor || null } }
  catch (failure) { if (alive && ticket === generation) error.value = userFacingError(failure, '执行历史无法读取，请重试。') }
}
async function refresh(strict = false) {
  const run = selected.value, ticket = generation; if (!run || loading.value && !strict) return
  const refreshTicket = ++refreshGeneration
  loading.value = true
  try {
    const value = await workflowRuns.attempt(props.requirement, props.node.id, run)
    if (!alive || ticket !== generation || refreshTicket !== refreshGeneration) return
    metadata.value = value; readable.value = true; error.value = ''
    history.value = history.value.map(item => item.id === value.id ? value : item)
    if (!definition.value) { const frozen = await workflowRuns.definition(props.requirement, props.node.id, run); if (!alive || ticket !== generation || refreshTicket !== refreshGeneration) return; definition.value = frozen }
    if (open.value) await loadBody(open.value)
  } catch (failure) { if (alive && ticket === generation && refreshTicket === refreshGeneration) { readable.value = false; error.value = userFacingError(failure, '节点记录无法读取，请重试。'); if (strict) throw failure } }
  finally { if (alive && ticket === generation && refreshTicket === refreshGeneration) { loading.value = false; schedule() } }
}
function schedule() { clearTimeout(timer); if (!terminal.value) timer = setTimeout(() => { if (document.hidden || command.locked.value) schedule(); else void refresh() }, 2500) }
async function choose(run: string) {
  if (selected.value === run) return
  if (selected.value && !canLeave()) return
  generation++; clearTimeout(timer); loading.value = false; selected.value = run; metadata.value = null; definition.value = null; readable.value = false; input.value = null; result.value = null; activity.value = null; commandEvidence.value = null; open.value = null
  humanSummary.value = ''; values.value = {}; outcome.value = ''; await refresh()
}
async function loadBody(kind: 'input' | 'result' | 'activity' | 'command' | 'knowledge' | 'partial') {
  const run = selected.value, ticket = generation; if (!run) return
  const evidenceTicket = kind === 'command' ? ++evidenceGeneration : evidenceGeneration
  try {
    if (kind === 'input' && !input.value) { const value = await workflowRuns.inputs(props.requirement, props.node.id, run); if (alive && ticket === generation) input.value = value }
    if (kind === 'result' && metadata.value?.deliveryAccepted && !result.value) { const value = await workflowRuns.result(props.requirement, props.node.id, run); if (alive && ticket === generation) result.value = value }
    if (kind === 'command' && (!commandEvidence.value || !terminal.value || !commandEvidence.value.result)) { const value = await workflowRuns.commandEvidence(props.requirement, props.node.id, run); if (alive && ticket === generation && evidenceTicket === evidenceGeneration) commandEvidence.value = value }
    if (kind === 'activity') { const value = await workflowRuns.activity(props.requirement, props.node.id, run); if (alive && ticket === generation) activity.value = value.connected || !activity.value ? value : { ...value, parts: activity.value.parts } }
  } catch (failure) { if (alive && ticket === generation && (kind !== 'command' || evidenceTicket === evidenceGeneration)) error.value = userFacingError(failure, '节点内容无法读取，请重试。') }
}
function show(kind: 'input' | 'result' | 'activity' | 'command' | 'knowledge' | 'partial') { open.value = open.value === kind ? null : kind; if (open.value) void loadBody(kind) }
async function complete() {
  if (!human.value || locked.value || !metadata.value) return
  try {
    if (!humanSummary.value.trim()) throw new Error('请填写本次结果说明。')
    if (details().outcomes.length && !outcome.value) throw new Error('请选择本次业务结果。')
    const requirement = props.requirement, nodeKey = props.node.id
    const body = { requestKey: crypto.randomUUID(), expectedVersion: props.version, attemptId: selected.value, expectedAttemptVersion: metadata.value.version,
      delivery: { summary: humanSummary.value.trim(), outcome: outcome.value || null, outputs: readValues(details().outputs, values.value) } }
    await command.submit('提交人工结果', () => workflowRuns.complete(requirement, nodeKey, body), async () => { humanSummary.value = ''; values.value = {}; emit('changed'); await refresh(true) })
  } catch (failure) { error.value = userFacingError(failure) }
}
async function executionAction(action: 'stop' | 'resume') {
  const isCommand = !!metadata.value?.commandState, version = isCommand ? metadata.value?.commandVersion : metadata.value?.modelVersion
  if (locked.value || version == null || !current.value) return
  if (action === 'stop' && !window.confirm('停止当前节点？程序将先确认停止并保留已有成果，后续派发也会暂停。')) return
  const run = selected.value, requirement = props.requirement, nodeKey = props.node.id, body = { requestKey: crypto.randomUUID(), expectedVersion: version }
  await command.submit(action === 'stop' ? '停止节点' : '恢复原尝试', () => isCommand ? workflowRuns.commandAction(requirement, nodeKey, run, action, body) : workflowRuns.modelAction(requirement, nodeKey, run, action, body), async () => { emit('changed'); await refresh(true) })
}
const dirty = computed(() => !!command.pending.value || !!humanSummary.value || !!outcome.value || Object.values(values.value).some(Boolean))
function canLeave() { return !dirty.value || window.confirm('当前节点有未提交或待确认的结果，仍要离开节点详情？') }
defineExpose({ canLeave })
watch(() => props.summary?.latestAttemptId, async (value, previous) => { if (value && (!selected.value || selected.value === previous)) await choose(value); if (alive) await list() }, { immediate: true })
watch(() => command.locked.value, value => emit('busy', value), { immediate: true })
const unload = (event: BeforeUnloadEvent) => { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { window.removeEventListener('beforeunload', unload); alive = false; generation++; clearTimeout(timer); emit('busy', false) })
</script>
<template><aside class="workflow-inspector workflow-run-inspector" aria-label="节点执行详情"><header><h2>{{ node.title }}</h2><StatusBadge :status="summary?.state || 'PENDING'" :label="workflowStateLabel(summary?.state || 'PENDING')" /></header><p v-if="!summary?.latestAttemptId">节点尚未开始，可选择单步执行或运行至此处。</p><details v-if="!metadata" class="workflow-fields" open><summary>任务与完成标准</summary><p>{{ node.task }}</p><p>{{ node.completion.criterion }}</p></details><template v-else><label class="workflow-field-label">执行尝试<select aria-label="执行尝试" :value="selected" :disabled="command.locked.value" @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="attempt in history" :key="attempt.id" :value="attempt.id">第 {{ attempt.ordinal }} 次 · {{ workflowStateLabel(attempt.state) }}</option></select></label><button v-if="cursor" @click="list(true)">更早的尝试</button><p v-if="loading && !metadata" role="status">正在读取节点…</p><div v-if="error || command.error.value" class="workflow-error" role="alert">{{ error || command.error.value }}<button :disabled="loading || command.busy.value" @click="command.pending.value ? command.retry() : refresh()">{{ command.pending.value?.accepted ? '刷新操作结果' : command.pending.value ? '重试原操作' : '重新读取' }}</button></div>
      <template v-if="metadata"><p class="workflow-inspector-hint">第 {{ metadata.ordinal }} 次 · {{ workflowStateLabel(metadata.state) }}<br />{{ metadata.roleName || (details().kind === 'SYSTEM' ? '程序执行' : '人工处理') }}<span v-if="metadata.roleRevisionNumber"> · 角色版本 {{ metadata.roleRevisionNumber }}</span></p><p v-if="metadata.queueState === 'QUEUED'">等待工作目录可用。</p><p v-if="metadata.suspended || metadata.state === 'FAILED' && metadata.errorCode" role="alert" class="workflow-error">{{ workflowReasonLabel(metadata.errorCode) }}</p><p v-if="metadata.deliveryAccepted && !terminal">已收到交付物，正在等待执行与资源收尾。</p><div v-if="current && processState && !terminal" class="workflow-inline"><button v-if="metadata.suspended" :disabled="locked" @click="executionAction('resume')">{{ processState === 'STOPPING' ? '重新检查停止' : '恢复原尝试' }}</button><button v-if="processState !== 'STOPPING'" class="danger" :disabled="locked" @click="executionAction('stop')">停止节点</button><span v-else>等待停止确认</span></div>
      <details class="workflow-fields"><summary>本次任务与完成标准</summary><p>{{ details().task }}</p><p>{{ details().completion.criterion }}</p></details>
      <nav class="workflow-detail-tabs" aria-label="节点内容"><button :aria-pressed="open === 'input'" @click="show('input')">固定输入</button><button :disabled="!metadata.deliveryAccepted" :aria-pressed="open === 'result'" @click="show('result')">交付物</button><button v-if="metadata.commandState" :aria-pressed="open === 'command'" @click="show('command')">执行记录</button><button v-if="metadata.modelState" :aria-pressed="open === 'activity'" @click="show('activity')">模型日志</button><button v-if="metadata.modelState" :aria-pressed="open === 'knowledge'" @click="show('knowledge')">检索证据</button><button v-if="details().moduleId === 'system.review.snapshot' && metadata.state === 'SUCCEEDED' && metadata.stopConfirmed" :aria-pressed="open === 'partial'" @click="show('partial')">阶段报告</button></nav>
      <section v-if="open === 'input' && input"><h3>本次固定输入</h3><p>{{ input.objective }}</p><article v-for="value in input.values" :key="value.name" class="workflow-result"><h4>{{ details().inputs.find(item => item.name === value.name)?.name || '输入资料' }}</h4><p>{{ value.source === 'NODE' ? '来自上游已接受交付物' : '来自需求公共资料' }}</p><WorkflowFiles v-if="['CODE', 'DOCUMENT'].includes(value.kind)" :key="`${selected}-input-${value.name}`" :requirement="requirement" :node="node.id" :attempt="selected" direction="inputs" :name="value.name" :archive="isDocument(value.content)" :changes="value.kind === 'CODE'" /><WorkflowInputContent v-else-if="value.reference" :key="`${selected}-input-${value.name}`" :requirement="requirement" :node="node.id" :attempt="selected" :input="value" :review="details().moduleId === 'system.review.dual' && value.kind === 'DECISION'" /><MarkdownDocument v-else-if="value.kind === 'TEXT'" :content="String(value.content)" :allow-images="false" /><WorkflowReviewReport v-else-if="details().moduleId === 'system.review.dual' && value.kind === 'DECISION'" :content="value.content" /><CodeMergeEditor v-else :model-value="JSON.stringify(value.content, null, 2)" language="json" readonly /></article></section>
      <section v-if="open === 'result' && result"><h3>本次交付物</h3><p>{{ result.delivery.summary }}</p><p v-if="result.delivery.outcome">{{ outcomeTitle(details(), result.delivery.outcome) }}</p><article v-for="(value, name) in result.delivery.outputs" :key="name" class="workflow-result"><h4>{{ details().outputs.find(item => item.name === name)?.title || '交付内容' }}</h4><WorkflowFiles v-if="['CODE', 'DOCUMENT'].includes(value.kind)" :key="`${selected}-output-${name}`" :requirement="requirement" :node="node.id" :attempt="selected" direction="outputs" :name="String(name)" :archive="isDocument(value.content)" :changes="value.kind === 'CODE'" /><MarkdownDocument v-else-if="value.kind === 'TEXT'" :content="String(value.content)" :allow-images="false" /><WorkflowKnowledgeReport v-else-if="details().moduleId === 'knowledge.research' && name === 'evidence'" :content="value.content" /><WorkflowDocumentReviewReport v-else-if="['document.direct-review', 'document.direct-review-check'].includes(details().moduleId || '') && ['assessment', 'review'].includes(String(name))" :content="value.content" :review="name === 'review'" /><WorkflowTestReviewReport v-else-if="details().moduleId === 'source.test-review' && name === 'review'" :content="value.content" /><WorkflowTestScopeReport v-else-if="details().moduleId === 'source.test-write' && name === 'scope'" :content="value.content" /><WorkflowTestDesignReport v-else-if="details().moduleId === 'source.test-design' && name === 'design'" :content="value.content" /><WorkflowSourceDesignReport v-else-if="['source.design', 'source.design-review'].includes(details().moduleId || '') && ['design', 'review'].includes(String(name))" :content="value.content" :review="name === 'review'" /><p v-else-if="value.kind === 'PLAN'">在画布顶部的“候选计划”中查看完整变更、确认状态和应用记录。</p><WorkflowTestProfileReport v-else-if="details().moduleId === 'system.source.test-profile' && ['profile', 'report'].includes(String(name))" :content="value.content" /><WorkflowSourcePlanReport v-else-if="['system.source.design-plan', 'system.source.test-plan'].includes(details().moduleId || '') && name === 'report'" :content="value.content" /><WorkflowDocumentPlanReport v-else-if="details().moduleId === 'system.document.review-plan' && name === 'report'" :content="value.content" /><WorkflowDocumentReport v-else-if="['system.source.design-document', 'system.document.review-report'].includes(details().moduleId || '') && name === 'report'" :content="value.content" /><WorkflowHistorySummary v-else-if="['system.history.plan', 'system.history.report'].includes(details().moduleId || '') && name === 'report'" :content="value.content" /><WorkflowSnapshotSummary v-else-if="['system.snapshot.plan', 'system.snapshot.report'].includes(details().moduleId || '') && name === 'report'" :content="value.content" /><WorkflowSnapshotReport v-else-if="['snapshot.analyze', 'snapshot.review'].includes(details().moduleId || '') && name === 'analysis'" :content="value.content" /><WorkflowHistoryAnalysisReport v-else-if="['history.review', 'history.contribution'].includes(details().moduleId || '') && name === 'analysis'" :content="value.content" /><WorkflowReviewSourceReport v-else-if="details().moduleId === 'system.review.snapshot' && name === 'report'" :content="value.content" /><WorkflowHistoryReport v-else-if="details().moduleId === 'system.git.history' && name === 'report'" :content="value.content" /><WorkflowRepositoryReport v-else-if="details().moduleId === 'system.repository.snapshot' && name === 'report'" :content="value.content" /><WorkflowSourceReport v-else-if="details().moduleId === 'system.source.snapshot' && name === 'report'" :content="value.content" /><WorkflowVerificationReport v-else-if="details().moduleId === 'system.verify.files' && name === 'report'" :content="value.content" /><WorkflowTestSummaryReport v-else-if="details().moduleId === 'system.source.test-summary' && name === 'report'" :content="value.content" /><WorkflowNativeTestReport v-else-if="details().moduleId === 'system.source.test-run' && name === 'report'" :content="value.content" /><WorkflowCommandReport v-else-if="details().moduleId === 'system.verify.command' && name === 'report'" :content="value.content" /><WorkflowReviewReport v-else-if="['review.requirement', 'review.risk', 'system.review.dual'].includes(details().moduleId || '') && ['review', 'report'].includes(String(name))" :content="value.content" /><CodeMergeEditor v-else :model-value="JSON.stringify(value.content, null, 2)" language="json" readonly /></article></section>
      <section v-if="open === 'command'"><button :disabled="loading" @click="commandEvidence = null; loadBody('command')">刷新执行记录</button><WorkflowCommandEvidence v-if="commandEvidence" :evidence="commandEvidence" :terminal="terminal" :repository="['system.repository.snapshot', 'system.git.history', 'system.review.snapshot'].includes(details().moduleId || '')" :history="details().moduleId === 'system.git.history'" :review-source="details().moduleId === 'system.review.snapshot'" /></section>
      <WorkflowSnapshotPartialReport v-if="open === 'partial' && details().moduleId === 'system.review.snapshot' && metadata.state === 'SUCCEEDED' && metadata.stopConfirmed" :key="`${requirement}-${node.id}-${selected}-partial`" :requirement="requirement" :node="node.id" :attempt="selected" />
      <WorkflowKnowledgeEvidence v-if="open === 'knowledge'" :key="`${selected}-knowledge`" :requirement="requirement" :node="node.id" :attempt="selected" />
      <section v-if="open === 'activity'"><p v-if="activity?.detail" role="status">{{ activity.detail }}</p><p v-if="activity?.truncated">仅展示最近的有界日志片段。</p><button :disabled="loading" @click="loadBody('activity')">刷新日志</button><article v-for="part in activity?.parts" :key="part.id" class="workflow-result"><strong>{{ part.label || '执行记录' }}</strong><pre>{{ part.content }}</pre></article></section>
      <form v-if="human" class="workflow-human-form" @submit.prevent="complete"><h3>填写人工结果</h3><fieldset class="workflow-fields" :disabled="locked"><label>结果说明<textarea v-model="humanSummary" rows="3" maxlength="4000" required /></label><label v-if="details().outcomes.length">业务结果<select v-model="outcome" required><option value="" disabled>请选择</option><option v-for="value in details().outcomes" :key="value" :value="value">{{ outcomeTitle(details(), value) }}</option></select></label></fieldset><WorkflowValueFields :fields="details().outputs" :values="values" :disabled="locked" @change="value => values = value" /><button class="primary-button" :disabled="locked">提交结果并完成节点</button></form></template></template></aside></template>
