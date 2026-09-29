<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [node: WorkflowNode] }>()
const paths = computed<string[] | null>(() => {
  const raw = props.node.parameters.targetPaths
  if (!raw) return []
  try { const value: unknown = JSON.parse(raw); return Array.isArray(value) && value.every(item => typeof item === 'string' && !/[\r\n]/.test(item)) ? value : null }
  catch { return null }
})
function change(value: string) {
  if (props.disabled) return
  const parameters = { ...props.node.parameters }, selected = value.split(/\r?\n/).filter(Boolean)
  if (selected.length) parameters.targetPaths = JSON.stringify(selected)
  else delete parameters.targetPaths
  emit('change', { ...props.node, parameters })
}
function reset() {
  if (!props.disabled && window.confirm('将现有路径配置替换为全部适用目标？')) change('')
}
</script>
<template>
  <fieldset class="workflow-fields" :disabled="disabled"><legend>本批源码范围</legend>
    <template v-if="paths"><label>本批源码文件<textarea :value="paths.join('\n')" rows="4" placeholder="每行一个项目内路径；留空使用全部适用目标" @input="change(($event.target as HTMLTextAreaElement).value)" /></label><p>每批最多 12 个文件、160000 字节；单个大文件独立一批。路径必须属于上游冻结资料的目标范围。</p></template>
    <template v-else><p role="alert">现有路径配置无法按列表编辑，原配置已保留。</p><button @click="reset">重新设置为全部适用目标</button></template>
    <p v-if="node.moduleId === 'source.design-review'">复核目标须与本批设计稿一致；需要检查其他模块时，在输入来源中绑定相关设计稿。</p>
  </fieldset>
</template>
