<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { TaskSessionPendingQuestion } from '@/types/domain'

const props = defineProps<{ pending: TaskSessionPendingQuestion, submitting?: boolean }>()
const emit = defineEmits<{
  submit: [answers: string[][]]
  reject: []
}>()

const answerDrafts = ref<string[][]>(props.pending.questions.map(() => []))
const customDrafts = ref<string[]>(props.pending.questions.map(() => ''))

function setSingleAnswer(index: number, value: string | number | boolean | undefined) {
  answerDrafts.value[index] = value === undefined || value === '' ? [] : [String(value)]
  customDrafts.value[index] = ''
}

function setMultipleAnswers(index: number, value: unknown) {
  answerDrafts.value[index] = Array.isArray(value) ? value.map(String) : []
}

function setCustomDraft(index: number, value: unknown) {
  customDrafts.value[index] = typeof value === 'string' ? value : String(value ?? '')
}

function answers() {
  return props.pending.questions.map((prompt, index) => {
    const selected = answerDrafts.value[index] ?? []
    const custom = customDrafts.value[index]?.trim() ?? ''
    if (!custom) return selected
    return prompt.multiple ? [...selected, custom] : [custom]
  })
}

function submit() {
  const value = answers()
  if (value.every((answer) => answer.length > 0)) emit('submit', value)
}
</script>

<template>
  <section class="designer-question-card" aria-label="Designer 等待回答">
    <header>
      <div><span>需要你的回答</span><strong class="mono">{{ pending.id }}</strong></div>
      <Icon icon="lucide:message-square-more" width="19" />
    </header>
    <div v-for="(prompt, index) in pending.questions" :key="`${pending.id}-${index}`" class="designer-question-prompt">
      <p>{{ prompt.header || `问题 ${index + 1}` }}</p>
      <h3>{{ prompt.question }}</h3>
      <el-checkbox-group v-if="prompt.multiple" :model-value="answerDrafts[index]" class="designer-question-options" @update:model-value="(value: unknown) => setMultipleAnswers(index, value)">
        <el-checkbox v-for="option in prompt.options" :key="option.label" :value="option.label" border>
          <span><b>{{ option.label }}</b><small>{{ option.description }}</small></span>
        </el-checkbox>
      </el-checkbox-group>
      <el-radio-group v-else :model-value="answerDrafts[index]?.[0] ?? ''" class="designer-question-options" @update:model-value="(value: string | number | boolean | undefined) => setSingleAnswer(index, value)">
        <el-radio v-for="option in prompt.options" :key="option.label" :value="option.label" border>
          <span><b>{{ option.label }}</b><small>{{ option.description }}</small></span>
        </el-radio>
      </el-radio-group>
      <el-input v-if="prompt.custom" :model-value="customDrafts[index]" class="designer-custom-answer" type="textarea" :rows="2" :placeholder="prompt.multiple ? '可补充自定义回答（会与已选项一起提交）' : '或输入自定义回答（会替代已选项）'" @update:model-value="(value: unknown) => setCustomDraft(index, value)" />
    </div>
    <footer>
      <el-button plain :disabled="submitting" @click="emit('reject')">拒绝</el-button>
      <el-button type="primary" :loading="submitting" :disabled="submitting || !answers().every((answer) => answer.length > 0)" @click="submit">提交回答并继续</el-button>
    </footer>
  </section>
</template>

<style scoped>
.designer-question-card { margin: 14px 0; overflow: hidden; border: 1px solid rgb(34 211 238 / 42%); border-radius: 12px; background: linear-gradient(135deg, rgb(34 211 238 / 9%), rgb(139 92 246 / 7%)); box-shadow: 0 14px 36px rgb(0 0 0 / 22%); }
.designer-question-card > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid rgb(34 211 238 / 24%); color: var(--color-accent-cyan); }
.designer-question-card > header > div { display: flex; align-items: baseline; gap: 10px; }
.designer-question-card > header span { font-size: 12px; font-weight: 800; }
.designer-question-card > header strong { color: var(--color-text-muted); font-size: 8px; font-weight: 500; }
.designer-question-prompt { padding: 14px; border-bottom: 1px solid var(--color-border-default); }
.designer-question-prompt > p { margin: 0 0 5px; color: #a78bfa; font: 800 9px/1.2 var(--font-code); letter-spacing: .08em; text-transform: uppercase; }
.designer-question-prompt h3 { margin: 0 0 12px; color: var(--color-text-primary); font-size: 12px; line-height: 1.6; }
.designer-question-options { display: grid; gap: 8px; }
.designer-question-options :deep(.el-radio), .designer-question-options :deep(.el-checkbox) { width: 100%; height: auto; min-height: 44px; margin: 0; padding: 8px 10px; white-space: normal; }
.designer-question-options :deep(.el-radio__label), .designer-question-options :deep(.el-checkbox__label) { width: 100%; white-space: normal; }
.designer-question-options span { display: grid; gap: 3px; }
.designer-question-options b { color: var(--color-text-primary); font-size: 10px; }
.designer-question-options small { color: var(--color-text-muted); font-size: 9px; line-height: 1.4; }
.designer-custom-answer { margin-top: 9px; }
.designer-question-card footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 14px; }
</style>
