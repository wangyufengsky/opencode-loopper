<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [node: WorkflowNode] }>()
type Check = { title: string; type: string; path: string; expected: string | null; matchMode: string | null }
type Spec = { version: number; inputName: string; checks: Check[] }
const spec = computed<Spec | null>(() => {
  try {
    const value = JSON.parse(props.node.parameters.verification || '{}') as Partial<Spec> | null
    if (!value || value.version !== 1 || typeof value.inputName !== 'string' || !Array.isArray(value.checks)
      || Object.keys(value).some(key => !['version', 'inputName', 'checks'].includes(key))) return null
    return value.checks.every(check => check && typeof check.title === 'string' && typeof check.path === 'string'
      && ['FILE_CONTENT', 'FILE_HASH', 'FILE_NOT_EXISTS'].includes(check.type)
      && (check.expected == null || typeof check.expected === 'string') && (check.matchMode == null || typeof check.matchMode === 'string')
      && Object.keys(check).every(key => ['title', 'type', 'path', 'expected', 'matchMode'].includes(key))) ? value as Spec : null
  }
  catch { return null }
})
function change(value: Spec) { if (!props.disabled) emit('change', { ...props.node, parameters: { ...props.node.parameters, verification: JSON.stringify(value) } }) }
function patch(index: number, value: Partial<Check>) { if (spec.value) change({ ...spec.value, checks: spec.value.checks.map((check, i) => i === index ? { ...check, ...value } : check) }) }
function add() { if (spec.value) change({ ...spec.value, checks: [...spec.value.checks, { title: '文件内容检查', type: 'FILE_CONTENT', path: '', expected: '', matchMode: 'CONTAINS' }] }) }
function remove(index: number) { if (spec.value && window.confirm('移除这项交付物检查？')) change({ ...spec.value, checks: spec.value.checks.filter((_, i) => i !== index) }) }
function reset() { if (window.confirm('重新配置此节点的检查项目？原配置将被替换。')) change({ version: 1, inputName: 'code', checks: [] }) }
</script>
<template><fieldset class="workflow-fields" :disabled="disabled"><legend>交付物检查</legend><template v-if="spec">
  <label>检查哪份输入<select :value="spec.inputName" @change="change({ ...spec, inputName: ($event.target as HTMLSelectElement).value })"><option v-for="input in node.inputs.filter(value => value.kind === 'CODE' && value.source === 'NODE' && value.required)" :key="input.name" :value="input.name">{{ input.name }}</option></select></label>
  <p v-if="!spec.checks.length">添加需要程序检查的文件和期望结果。</p>
  <div v-for="(check, index) in spec.checks" :key="index" class="workflow-binding">
    <label>检查名称<input :value="check.title" maxlength="120" @input="patch(index, { title: ($event.target as HTMLInputElement).value })" /></label>
    <label>检查类型<select :value="check.type" @change="patch(index, { type: ($event.target as HTMLSelectElement).value, expected: '', matchMode: 'CONTAINS' })"><option value="FILE_CONTENT">文件内容</option><option value="FILE_HASH">文件 SHA-256</option><option value="FILE_NOT_EXISTS">文件已移除</option></select></label>
    <label>项目内文件路径<input :value="check.path" placeholder="例如 src/config.json" maxlength="1024" @input="patch(index, { path: ($event.target as HTMLInputElement).value })" /></label>
    <label v-if="check.type === 'FILE_CONTENT'">匹配方式<select :value="check.matchMode" @change="patch(index, { matchMode: ($event.target as HTMLSelectElement).value })"><option value="CONTAINS">包含期望文本</option><option value="EXACT">与期望文本完全相同</option></select></label>
    <label v-if="check.type !== 'FILE_NOT_EXISTS'">{{ check.type === 'FILE_HASH' ? '期望 SHA-256' : '期望文本' }}<textarea :value="check.expected || ''" :maxlength="check.type === 'FILE_HASH' ? 64 : 4000" rows="3" @input="patch(index, { expected: ($event.target as HTMLTextAreaElement).value })" /></label>
    <button @click="remove(index)">移除检查</button>
  </div><button :disabled="spec.checks.length >= 16" @click="add">＋ 添加检查项目</button>
</template><template v-else><p role="alert">当前检查配置无法读取，可以重新配置。</p><button @click="reset">重新配置检查</button></template></fieldset></template>
