<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [node: WorkflowNode] }>()
type Section = { fileId: string; section: number }
const selection = computed<Section[] | null>(() => {
  const raw = props.node.parameters.documentSections
  if (!raw) return []
  try { const values: unknown = JSON.parse(raw); return Array.isArray(values) && values.every(item => item && typeof item.fileId === 'string' && /^DOC-[1-9][0-9]?$/.test(item.fileId) && Number.isSafeInteger(item.section) && item.section > 0) ? values : null }
  catch { return null }
})
function patch(value: Record<string, string>) { if (!props.disabled) emit('change', { ...props.node, parameters: { ...props.node.parameters, ...value } }) }
function choose(mode: string) { patch({ documentSections: mode === 'ALL' ? '' : JSON.stringify([{ fileId: 'DOC-1', section: 1 }]) }) }
function change(index: number, field: 'fileId' | 'section', value: string) {
  const number = Number(value)
  if (!selection.value || !Number.isSafeInteger(number) || number < 1 || field === 'fileId' && number > 99) return
  patch({ documentSections: JSON.stringify(selection.value.map((item, i) => i === index ? { ...item, [field]: field === 'fileId' ? `DOC-${number}` : number } : item)) })
}
function batch(value: string) { const number = Number(value); if (Number.isSafeInteger(number) && number >= 1 && number <= 512) patch({ documentBatchOrdinal: String(number - 1) }) }
function reset() { if (!props.disabled && window.confirm('将现有章节配置替换为全部原文？')) choose('ALL') }
</script>
<template>
  <fieldset class="workflow-fields" :disabled="disabled"><legend>本批原文范围</legend>
    <label>批次序号<input aria-label="评审批次序号" type="number" min="1" max="512" :value="Number(node.parameters.documentBatchOrdinal || 0) + 1" @change="batch(($event.target as HTMLInputElement).value)" /></label>
    <template v-if="selection">
      <label>原文章节<select aria-label="评审原文章节" :value="node.parameters.documentSections ? 'SELECTED' : 'ALL'" @change="choose(($event.target as HTMLSelectElement).value)"><option value="ALL">全部原文</option><option value="SELECTED">指定章节</option></select></label>
      <template v-if="node.parameters.documentSections"><div v-for="(item, index) in selection" :key="index" class="workflow-binding"><label>文档序号<input :aria-label="`原文 ${index + 1} 文档序号`" type="number" min="1" max="99" :value="item.fileId.slice(4)" @change="change(index, 'fileId', ($event.target as HTMLInputElement).value)" /></label><label>章节序号<input :aria-label="`原文 ${index + 1} 章节序号`" type="number" min="1" :value="item.section" @change="change(index, 'section', ($event.target as HTMLInputElement).value)" /></label><button @click="patch({ documentSections: JSON.stringify(selection.filter((_, i) => i !== index)) })">移除章节</button></div><p v-if="!selection.length" role="alert">请至少选择一个章节，或改为全部原文。</p><button :disabled="selection.length >= 256" @click="patch({ documentSections: JSON.stringify([...selection, { fileId: 'DOC-1', section: 1 }]) })">添加章节</button></template>
    </template>
    <template v-else><p role="alert">现有章节配置无法编辑，原配置已保留。</p><button @click="reset">重新设置为全部原文</button></template>
    <p>文档序号按上传顺序，章节按解析目录从 1 开始。每批最多 256 章、48000 字符；单个超长章节独立成批。</p>
    <p v-if="node.moduleId === 'document.direct-review-check'">批次与章节须和本批评审稿一致。复核独立读取原文、代码及全部绑定稿件，保留真实通过或返修意见。</p>
  </fieldset>
</template>
