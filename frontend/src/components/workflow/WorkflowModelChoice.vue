<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { AvailableModel, WorkflowModel } from '@/types/domain'
const model = defineModel<WorkflowModel | null>({ required: true })
defineProps<{ disabled?: boolean }>()
const choices = ref<AvailableModel[]>([]), loading = ref(false), error = ref('')
let alive = true
async function load() {
  if (loading.value) return
  loading.value = true; error.value = ''
  try { const values = await api.getSettingsModels(); if (alive) choices.value = values }
  catch { if (alive) error.value = '模型列表暂时无法读取，请检查运行环境后重试。' }
  finally { if (alive) loading.value = false }
}
function select(value: string) {
  const found = choices.value.find(item => item.id === value)
  if (found) model.value = { providerId: found.provider, modelId: found.model, thinking: model.value?.thinking ?? null }
}
onMounted(async () => { if (model.value) return; try { const settings = await api.getSettings(); if (alive && !model.value && settings.openCode.provider && settings.openCode.model) model.value = { providerId: settings.openCode.provider, modelId: settings.openCode.model, thinking: null } } catch { if (alive) error.value = '默认模型无法读取，请展开列表选择。' } })
onBeforeUnmount(() => { alive = false })
</script>
<template><div class="workflow-fields"><label>执行模型<select :disabled="disabled" :value="model ? `${model.providerId}/${model.modelId}` : ''" @focus="!choices.length && load()" @change="select(($event.target as HTMLSelectElement).value)"><option value="" disabled>选择模型</option><option v-if="model && !choices.some(item => item.provider === model?.providerId && item.model === model?.modelId)" :value="`${model.providerId}/${model.modelId}`">{{ model.providerId }} / {{ model.modelId }}</option><option v-for="item in choices" :key="item.id" :value="item.id">{{ item.label || item.id }}</option></select></label><p v-if="loading" role="status">正在读取模型…</p><p v-if="error" role="alert">{{ error }}<button :disabled="loading || disabled" @click="load">重试</button></p></div></template>
