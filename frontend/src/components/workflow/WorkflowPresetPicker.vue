<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { workflowApi } from '@/api/workflow'
import type { WorkflowGraph, WorkflowNode, WorkflowPreset, WorkflowPresetSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { appendPreset, presetSources } from './presets'
const props = defineProps<{ graph: WorkflowGraph; disabled?: boolean }>()
const emit = defineEmits<{ insert: [graph: WorkflowGraph, node: WorkflowNode]; close: [] }>()
const query = ref(''), rows = ref<WorkflowPresetSummary[]>([]), cursor = ref<string | null>(null), selected = ref<WorkflowPreset | null>(null), values = ref<Record<string, string>>({}), loading = ref(false), error = ref('')
let generation = 0, catalogQuery = ''
async function list(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''; if (!more) { rows.value = []; cursor.value = null; selected.value = null }
  if (!more) catalogQuery = query.value
  try { const result = await workflowApi.presets(catalogQuery, more ? cursor.value || '' : ''); if (ticket !== generation) return; rows.value = more ? [...rows.value, ...result.items] : result.items; cursor.value = result.nextCursor || null }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '预设目录无法读取，请重试。') }
  finally { if (ticket === generation) loading.value = false }
}
async function choose(row: WorkflowPresetSummary) {
  const ticket = ++generation; loading.value = true; error.value = ''; selected.value = null; values.value = {}
  try { const value = await workflowApi.preset(row.id, row.version); if (ticket === generation) selected.value = value }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '预设或角色版本无法读取，请重新选择。') }
  finally { if (ticket === generation) loading.value = false }
}
function insert() {
  if (!selected.value || loading.value || props.disabled) return
  try { const value = appendPreset(props.graph, selected.value, values.value); emit('insert', value.graph, value.node) }
  catch (failure) { error.value = userFacingError(failure) }
}
onMounted(() => void list()); onBeforeUnmount(() => { generation++ })
</script>
<template><section class="workflow-presets" aria-label="预设工作模块"><header class="workflow-inline"><h2>预设工作模块</h2><input v-model="query" aria-label="搜索预设模块" placeholder="按任务搜索" @keydown.enter.prevent="list()" /><button :disabled="loading || disabled" @click="list()">搜索</button><button @click="emit('close')">收起预设</button></header><p v-if="loading" role="status">正在读取预设…</p><p v-if="error" role="alert" class="workflow-error">{{ error }}<button :disabled="loading" @click="list()">重试</button></p><div class="workflow-preset-content"><div class="workflow-preset-list"><button v-for="row in rows" :key="`${row.id}-${row.version}`" :aria-pressed="selected?.id === row.id" :disabled="loading || disabled" @click="choose(row)"><strong>{{ row.title }}</strong><small>{{ row.description }}</small></button><button v-if="cursor" :disabled="loading || disabled" @click="list(true)">更多预设</button><p v-if="!rows.length && !loading && !error">没有匹配的预设，仍可使用自由任务。</p></div><div v-if="selected" class="workflow-preset-detail"><h3>{{ selected.title }}</h3><p>{{ selected.roleName ? `${selected.roleName} · 版本 ${selected.roleRevisionNumber}` : selected.node.kind === 'SYSTEM' ? '程序执行' : '人工节点' }}</p><p class="workflow-objective">{{ selected.node.task }}</p><fieldset class="workflow-fields" :disabled="loading || disabled"><legend v-if="selected.inputs.length">输入来源</legend><label v-for="input in selected.inputs" :key="input.name">{{ input.title }}{{ input.required ? '（必需）' : '（可选）' }}<select v-model="values[input.name]" :aria-label="`${input.title}来源`"><option value="">{{ input.required ? '请选择来源' : '不绑定额外资料' }}</option><option v-for="source in presetSources(graph, input.kind)" :key="source.value" :value="source.value">{{ source.title }}</option></select></label></fieldset><p>交付物：{{ selected.node.outputs.map(output => output.title).join('、') }}</p><button class="primary-button" :disabled="loading || disabled" @click="insert">添加到画布</button></div></div></section></template>
