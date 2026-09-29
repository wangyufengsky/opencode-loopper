<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import WorkflowPlanDiff from './WorkflowPlanDiff.vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCandidate, WorkflowCandidateSummary } from '@/types/domain'
import { userFacingError, workflowCandidateStateLabel } from '@/utils/displayLabels'
import { useWorkflowCommand } from './command'
const props = defineProps<{ requirement: string; disabled?: boolean }>()
const emit = defineEmits<{ review: [value: WorkflowCandidate]; changed: []; close: []; busy: [value: boolean] }>()
const command = useWorkflowCommand(), rows = ref<WorkflowCandidateSummary[]>([]), cursor = ref<string | null>(null), filter = ref('PENDING'), selected = ref<WorkflowCandidate | null>(null), error = ref(''), loading = ref(false), reason = ref('')
let generation = 0, alive = true
async function list(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''; if (!more) { rows.value = []; cursor.value = null }
  try { const page = await workflowRuns.candidates(props.requirement, filter.value, more ? cursor.value || '' : ''); if (alive && ticket === generation) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null } }
  catch (failure) { if (alive && ticket === generation) { error.value = userFacingError(failure, '候选列表无法读取，请重试。'); throw failure } }
  finally { if (alive && ticket === generation) loading.value = false }
}
async function read(key: string) {
  const ticket = ++generation; loading.value = true; error.value = ''; selected.value = null; reason.value = ''
  try { const value = await workflowRuns.candidate(props.requirement, key); if (alive && ticket === generation) selected.value = value }
  catch (failure) { if (alive && ticket === generation) error.value = userFacingError(failure, '候选内容无法读取，请重新选择。') }
  finally { if (alive && ticket === generation) loading.value = false }
}
async function reject() {
  const value = selected.value; if (!value || value.state !== 'PENDING' || command.locked.value || props.disabled) return
  if (!window.confirm('退回这份候选计划？当前生效计划和来源节点交付物将保留。')) return
  const id = props.requirement, key = value.id, body = { requestKey: crypto.randomUUID(), expectedCandidateVersion: value.version, reason: reason.value.trim() }
  await command.submit('退回候选计划', () => workflowRuns.rejectCandidate(id, key, body), async () => { selected.value = null; emit('changed'); await list() })
}
function canLeave() { return !command.pending.value || window.confirm('候选操作结果尚待确认，仍要离开？重新打开后请先查看它的最新状态。') }
defineExpose({ canLeave })
function safeList(more = false) { void list(more).catch(() => {}) }
watch(() => command.locked.value, value => emit('busy', value), { immediate: true })
onMounted(() => safeList()); onBeforeUnmount(() => { alive = false; generation++; emit('busy', false) })
</script>
<template><section class="workflow-candidates" aria-label="候选计划"><header class="workflow-inline"><h2>候选计划</h2><select v-model="filter" aria-label="候选状态" :disabled="command.locked.value || disabled" @change="selected = null; safeList()"><option value="PENDING">待确认</option><option value="">全部历史</option></select><button :disabled="command.locked.value || disabled" @click="safeList()">刷新候选</button><button :disabled="command.locked.value" @click="emit('close')">收起候选</button></header><p v-if="loading" role="status">正在读取候选计划…</p><div v-if="error || command.error.value" class="workflow-error" role="alert">{{ error || command.error.value }}<button :disabled="command.busy.value" @click="command.pending.value ? command.retry() : safeList()">{{ command.pending.value?.accepted ? '刷新操作结果' : command.pending.value ? '重试原操作' : '重新读取' }}</button></div><div class="workflow-candidate-content"><div class="workflow-candidate-list"><button v-for="row in rows" :key="row.id" :disabled="command.locked.value || disabled" :aria-pressed="selected?.id === row.id" @click="read(row.id)"><strong>{{ row.sourceTitle }}</strong><span>基于计划 {{ row.baseRevision }} · {{ workflowCandidateStateLabel(row.state) }}{{ row.stale && row.state === 'PENDING' ? ' · 基准已过期' : '' }}</span></button><p v-if="!loading && !error && !rows.length">暂无候选计划。</p><button v-if="cursor" :disabled="loading || command.locked.value" @click="safeList(true)">更早的候选</button></div><div v-if="selected" class="workflow-candidate-detail"><h3>{{ selected.sourceTitle }} 提出的计划</h3><p>新增 {{ selected.changes.added.length }} 个节点，修改 {{ selected.changes.changed.length }} 个节点，移除 {{ selected.changes.removed.length }} 个节点。</p><WorkflowPlanDiff :before="selected.originalGraph" :after="selected.graph" /><p v-if="selected.stale && selected.state === 'PENDING'" role="alert">当前计划已有新版本，请退回这份过期候选后重新规划。</p><p v-if="!selected.sourceCompleted && selected.state === 'PENDING'">来源节点尚未成功收尾，可以先查看草案。</p><p v-if="selected.appliedRevision">应用结果保存在计划版本 {{ selected.appliedRevision }}；这里保留原始候选。</p><p v-if="selected.decisionReason">{{ selected.decisionReason }}</p><ul v-if="selected.diagnostics.length"><li v-for="(issue, index) in selected.diagnostics" :key="index">{{ issue.message }}</li></ul><div class="workflow-inline"><button :disabled="command.locked.value || disabled" @click="emit('review', selected)">在画布中查看</button><template v-if="selected.state === 'PENDING'"><label>退回说明<input v-model="reason" maxlength="4000" :disabled="command.locked.value || disabled" /></label><button class="danger" :disabled="command.locked.value || disabled" @click="reject">退回候选</button></template></div></div></div></section></template>
