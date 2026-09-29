<script setup lang="ts">
import type { WorkflowInput, WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [node: WorkflowNode] }>()
function mode(value: string) {
  if (props.disabled || !['FULL', 'DATE_INCREMENTAL'].includes(value)) return
  const inputs = props.node.inputs.filter(input => !['startDate', 'endDate'].includes(input.name))
  if (value === 'DATE_INCREMENTAL') for (const name of ['startDate', 'endDate']) inputs.push(props.node.inputs.find(input => input.name === name)
    ?? { name, source: 'REQUIREMENT', sourceId: name, output: null, kind: 'TEXT', required: true } satisfies WorkflowInput)
  emit('change', { ...props.node, inputs, parameters: { ...props.node.parameters, reviewMode: value } })
}
function timeout(value: string) {
  if (!props.disabled) emit('change', { ...props.node, parameters: { ...props.node.parameters, reviewTimeoutSeconds: value } })
}
</script>
<template><fieldset :disabled="disabled" class="workflow-fields" aria-label="版本审查采集设置"><legend>版本审查采集</legend>
  <label>审查范围<select aria-label="版本审查范围" :value="node.parameters.reviewMode || 'DATE_INCREMENTAL'" @change="mode(($event.target as HTMLSelectElement).value)"><option value="DATE_INCREMENTAL">日期增量</option><option value="FULL">全面审查</option></select></label>
  <label>采集时限（秒）<input aria-label="版本审查采集时限" type="number" min="10" max="600" :value="node.parameters.reviewTimeoutSeconds || '600'" @input="timeout(($event.target as HTMLInputElement).value)" /></label>
  <p v-if="node.parameters.reviewMode === 'FULL'">固定所选分支的当前提交，采集项目范围内的代码。</p>
  <p v-else>绑定开始、结束日期，按北京时间选择两个边界版本并比较最终代码。切换范围后请核对上方输入来源。</p>
  <p>重试沿用原提交和范围。采集只固定资料，审查结论由后续节点交付。</p>
</fieldset></template>
