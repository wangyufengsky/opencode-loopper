<script setup lang="ts">
import { ref } from 'vue'
import type { WorkflowGraph, WorkflowOutput } from '@/types/domain'
const props = defineProps<{ graph: WorkflowGraph; disabled?: boolean }>()
const emit = defineEmits<{ change: [value: WorkflowGraph] }>()
const error = ref('')
function patch(index: number, value: Partial<WorkflowOutput>) {
  if (props.disabled) return
  const previous = props.graph.inputs[index]!, next = { ...previous, ...value }
  error.value = ''
  if (props.graph.inputs.some((input, i) => i !== index && input.name === next.name)) { error.value = '引用名称已被使用，请换一个名称。'; return }
  emit('change', { ...props.graph, inputs: props.graph.inputs.map((input, i) => i === index ? next : input), nodes: props.graph.nodes.map(node => ({ ...node, inputs: node.inputs.map(input => input.source === 'REQUIREMENT' && input.sourceId === previous.name ? { ...input, sourceId: next.name, kind: next.kind } : input) })) })
}
function add() { if (props.disabled) return; let index = 1; while (props.graph.inputs.some(item => item.name === `input${index}`)) index++; emit('change', { ...props.graph, inputs: [...props.graph.inputs, { name: `input${index}`, title: '公共资料', kind: 'TEXT', required: true }] }) }
function remove(index: number) {
  if (props.disabled) return
  error.value = ''
  if (props.graph.nodes.some(node => node.inputs.some(input => input.source === 'REQUIREMENT' && input.sourceId === props.graph.inputs[index]?.name))) { error.value = '公共资料仍被节点使用，请先调整节点输入。'; return }
  if (window.confirm('移除这项公共资料声明？')) emit('change', { ...props.graph, inputs: props.graph.inputs.filter((_, i) => i !== index) })
}
</script>
<template><fieldset class="workflow-fields" :disabled="disabled"><legend>公共资料设置</legend><div v-for="(item, index) in graph.inputs" :key="index" class="workflow-binding"><label>资料名称<input :value="item.title" @input="patch(index, { title: ($event.target as HTMLInputElement).value })" /></label><label>引用名称<input :value="item.name" @change="patch(index, { name: ($event.target as HTMLInputElement).value })" /></label><label>内容类型<select :value="item.kind" @change="patch(index, { kind: ($event.target as HTMLSelectElement).value as WorkflowOutput['kind'] })"><option value="TEXT">文本</option><option value="JSON">结构化数据</option><option value="DOCUMENT">上传文档</option></select></label><label><input type="checkbox" :checked="item.required" @change="patch(index, { required: ($event.target as HTMLInputElement).checked })" />必需</label><button type="button" @click="remove(index)">移除资料</button></div><p v-if="error" role="alert">{{ error }}</p><button type="button" @click="add">＋ 添加公共资料</button></fieldset></template>
