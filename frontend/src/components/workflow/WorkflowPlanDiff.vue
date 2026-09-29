<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowGraph, WorkflowNode } from '@/types/domain'
const props = defineProps<{ before: WorkflowGraph; after: WorkflowGraph }>()
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const incoming = (graph: WorkflowGraph, key: string) => graph.edges.filter(edge => edge.to === key).sort((a, b) => a.id.localeCompare(b.id))
const added = computed(() => props.after.nodes.filter(node => !props.before.nodes.some(old => old.id === node.id)))
const removed = computed(() => props.before.nodes.filter(node => !props.after.nodes.some(next => next.id === node.id)))
const changed = computed(() => props.after.nodes.flatMap(next => { const old = props.before.nodes.find(node => node.id === next.id); return old && (!same(old, next) || !same(incoming(props.before, next.id), incoming(props.after, next.id))) ? [{ old, next }] : [] }))
function fields(old: WorkflowNode, next: WorkflowNode) {
  const changes: string[] = []
  if (!same(old.inputs, next.inputs)) changes.push('输入来源')
  if (!same(old.outputs, next.outputs)) changes.push('预期交付物')
  if (!same(old.completion, next.completion)) changes.push('完成规则')
  if (!same(old.outcomes, next.outcomes)) changes.push('业务结果')
  if (old.roleId !== next.roleId || old.roleRevisionId !== next.roleRevisionId) changes.push('角色或版本')
  if (old.moduleId !== next.moduleId || old.moduleVersion !== next.moduleVersion || old.kind !== next.kind) changes.push('工作类型')
  if (old.maxRetries !== next.maxRetries) changes.push('重试次数')
  if (old.pauseAfter !== next.pauseAfter) changes.push('执行后暂停')
  if (!same(old.parameters, next.parameters)) changes.push('工作参数')
  if (!same(incoming(props.before, old.id), incoming(props.after, next.id))) changes.push('前置依赖')
  return changes.join('、')
}
const affected = computed(() => {
  const ids = new Set([...added.value, ...removed.value, ...changed.value.map(value => value.next)].map(node => node.id)), direct = new Set(ids)
  let more = true
  while (more) { more = false; for (const edge of [...props.before.edges, ...props.after.edges]) if (ids.has(edge.from) && !ids.has(edge.to)) { ids.add(edge.to); more = true } }
  return props.after.nodes.filter(node => ids.has(node.id) && !direct.has(node.id))
})
</script>
<template><details class="workflow-plan-diff"><summary>查看变更：新增 {{ added.length }}，修改 {{ changed.length }}，移除 {{ removed.length }}</summary><ul><li v-for="node in added" :key="`add-${node.id}`"><strong>新增 · {{ node.title }}</strong><p>{{ node.task }}</p></li><li v-for="entry in changed" :key="`change-${entry.next.id}`"><strong>修改 · {{ entry.next.title }}</strong><p v-if="entry.old.title !== entry.next.title">原名称：{{ entry.old.title }}</p><div v-if="entry.old.task !== entry.next.task" class="workflow-task-diff"><p><b>原任务</b>{{ entry.old.task }}</p><p><b>调整后</b>{{ entry.next.task }}</p></div><p v-if="fields(entry.old, entry.next)">调整项：{{ fields(entry.old, entry.next) }}。可在画布选中节点查看。</p></li><li v-for="node in removed" :key="`remove-${node.id}`"><strong>移除 · {{ node.title }}</strong><p>{{ node.task }}</p></li></ul><p v-if="affected.length">受影响的后续节点：{{ affected.map(node => node.title).join('、') }}</p><p v-if="!added.length && !changed.length && !removed.length">节点与依赖保持原样。</p></details></template>
