<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [node: WorkflowNode] }>()
type Spec = { version: number; inputName: string; argv: string[]; timeoutSeconds: number; purpose: 'TEST' | 'CHECK'; outputContains: string | null }
const spec = computed<Spec | null>(() => {
  try {
    const value = JSON.parse(props.node.parameters.commandVerification || '{}') as Partial<Spec> | null
    if (!value || value.version !== 1 || typeof value.inputName !== 'string' || !Array.isArray(value.argv)
      || !value.argv.every(arg => typeof arg === 'string') || !Number.isInteger(value.timeoutSeconds)
      || !['TEST', 'CHECK'].includes(value.purpose || '') || value.outputContains != null && typeof value.outputContains !== 'string'
      || Object.keys(value).some(key => !['version', 'inputName', 'argv', 'timeoutSeconds', 'purpose', 'outputContains'].includes(key))) return null
    return value as Spec
  } catch { return null }
})
const sources = computed(() => props.node.inputs.filter(input => input.kind === 'CODE' && input.source === 'NODE' && input.required))
function change(value: Spec) { if (!props.disabled) emit('change', { ...props.node, parameters: { ...props.node.parameters, commandVerification: JSON.stringify(value) } }) }
function patch(value: Partial<Spec>) { if (spec.value) change({ ...spec.value, ...value }) }
function argument(index: number, value: string) { if (spec.value) { const argv = [...spec.value.argv]; argv[index] = value; patch({ argv }) } }
function reset() { if (window.confirm('重新配置此节点的检查命令？原配置将被替换。')) change({ version: 1, inputName: sources.value[0]?.name || 'code', argv: [], timeoutSeconds: 300, purpose: 'TEST', outputContains: null }) }
</script>
<template><fieldset class="workflow-fields" :disabled="disabled"><legend>检查命令</legend><template v-if="spec">
  <label>检查哪份代码<select :value="spec.inputName" @change="patch({ inputName: ($event.target as HTMLSelectElement).value })"><option v-if="!sources.some(input => input.name === spec?.inputName)" :value="spec.inputName" disabled>请选择有效的上游代码输入</option><option v-for="input in sources" :key="input.name" :value="input.name">{{ input.name }}</option></select></label>
  <label>检查用途<select :value="spec.purpose" @change="patch({ purpose: ($event.target as HTMLSelectElement).value as Spec['purpose'] })"><option value="TEST">运行测试</option><option value="CHECK">运行其他检查</option></select></label>
  <label>执行程序<input :value="spec.argv[0] || ''" maxlength="4096" placeholder="例如 mvn、npm 或 ./check" @input="argument(0, ($event.target as HTMLInputElement).value)" /></label>
  <p class="workflow-inspector-hint">每项填写一个参数；参数中包含空格时无需额外加引号。</p>
  <div v-for="(arg, index) in spec.argv.slice(1)" :key="index" class="workflow-binding"><label>参数 {{ index + 1 }}<input :value="arg" maxlength="4096" @input="argument(index + 1, ($event.target as HTMLInputElement).value)" /></label><button :aria-label="`移除参数 ${index + 1}`" @click="patch({ argv: spec.argv.filter((_, i) => i !== index + 1) })">移除参数</button></div>
  <button :disabled="!spec.argv[0]?.trim() || spec.argv.length >= 128" @click="patch({ argv: [...spec.argv, ''] })">＋ 添加命令参数</button>
  <label>最长执行秒数<input type="number" min="1" max="3600" :value="spec.timeoutSeconds" @change="patch({ timeoutSeconds: Number(($event.target as HTMLInputElement).value) })" /></label>
  <label>输出须包含（可选）<textarea :value="spec.outputContains || ''" maxlength="4000" rows="3" placeholder="按原样匹配，保留空格和换行" @input="patch({ outputContains: ($event.target as HTMLTextAreaElement).value || null })" /></label>
</template><template v-else><p role="alert">当前命令配置无法读取，可以重新配置。</p><button @click="reset">重新配置命令</button></template></fieldset></template>
