<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { StoryBindingCapability, StoryBindingConfiguration } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ projectId: string; runtimeIdentity?: string; disabled?: boolean }>()
const model = defineModel<StoryBindingConfiguration>({ required: true })
const capability = ref<StoryBindingCapability>()
const checking = ref(false)
const failure = ref('')
let generation = 0
const available = computed(() => capability.value?.available === true)
const reason = computed(() => checking.value ? '正在检测 OpenCode aicoding 命令…'
  : failure.value || capability.value?.reason || '选择项目后检测故事绑定能力')

async function detect() {
  const requestedGeneration = ++generation
  capability.value = undefined
  failure.value = ''
  if (!props.projectId || props.disabled) {
    checking.value = false
    model.value = { ...model.value, enabled: false }
    return
  }
  checking.value = true
  try {
    const result = await api.getStoryBindingCapability(props.projectId)
    if (requestedGeneration !== generation) return
    capability.value = result
    if (!result.available) model.value = { ...model.value, enabled: false }
  } catch (error) {
    if (requestedGeneration !== generation) return
    failure.value = userFacingError(error, '无法检测当前 OpenCode 命令')
    model.value = { ...model.value, enabled: false }
  } finally {
    if (requestedGeneration === generation) checking.value = false
  }
}

function updateField(field: 'systemCode' | 'storyCode', value: string) {
  model.value = { ...model.value, [field]: value }
}

watch(() => [props.projectId, props.runtimeIdentity], () => {
  model.value = { enabled: false }
  void detect()
}, { immediate: true })
</script>

<template>
  <section class="story-binding-setup" aria-label="故事绑定设置">
    <div class="story-binding-heading">
      <label for="story-binding-switch"><strong>开启故事绑定</strong><span>自动统计本次设计和执行的 AI 工作量</span></label>
      <el-switch id="story-binding-switch" :model-value="model.enabled" :loading="checking"
        :disabled="disabled || checking || !available" aria-label="开启故事绑定"
        @update:model-value="model = { ...model, enabled: $event === true }" />
    </div>
    <div class="story-binding-detection" role="status" aria-live="polite">
      <span>{{ reason }}</span>
      <el-button text size="small" :disabled="disabled || checking || !projectId" @click="detect">重新检测</el-button>
    </div>
    <div v-if="model.enabled" class="story-binding-fields">
      <label for="story-system-code">系统编号 <span aria-hidden="true">*</span>
        <el-input id="story-system-code" :model-value="model.systemCode ?? ''" :disabled="disabled"
          maxlength="128" placeholder="例如 ZH-0737" aria-label="系统编号" aria-required="true"
          @update:model-value="updateField('systemCode', $event)" />
      </label>
      <label for="story-code">故事编号 <span aria-hidden="true">*</span>
        <el-input id="story-code" :model-value="model.storyCode ?? ''" :disabled="disabled"
          maxlength="128" placeholder="例如 001327" aria-label="故事编号" aria-required="true"
          @update:model-value="updateField('storyCode', $event)" />
      </label>
    </div>
  </section>
</template>

<style scoped>
.story-binding-setup { border-top: 1px solid var(--color-border-default); padding: 18px 24px; }
.story-binding-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.story-binding-heading label { display: grid; gap: 5px; font-size: 13px; }
.story-binding-heading label span, .story-binding-detection { color: var(--color-text-secondary); font-size: 12px; }
.story-binding-detection { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; }
.story-binding-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 12px; }
.story-binding-fields label { display: grid; gap: 7px; font-size: 12px; }
@media (max-width: 600px) { .story-binding-fields { grid-template-columns: 1fr; } }
</style>
