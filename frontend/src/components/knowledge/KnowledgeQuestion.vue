<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeQuestion } from '@/types/domain'
const props = defineProps<{ question: KnowledgeQuestion; conversation: string }>()
const emit = defineEmits<{ answered: [] }>()
const id = useId(), choices = ref<string[][]>(props.question.questions.map(() => [])), custom = ref<string[]>(props.question.questions.map(() => ''))
const busy = ref(false), error = ref('')
const storage = `loopper.knowledge.reply.${props.conversation}.${props.question.id}`
const draftKey = `${storage}.draft`
try {
  const prepared = JSON.parse(sessionStorage.getItem(storage) || 'null')
  const draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null')
  if (prepared?.answers) {
    props.question.questions.forEach((prompt, index) => {
      const values: string[] = prepared.answers[index] || []
      choices.value[index] = values.filter(value => prompt.options.some(option => option.label === value))
      custom.value[index] = values.filter(value => !choices.value[index]!.includes(value)).join('；')
    })
  } else if (draft?.choices && draft?.custom) { choices.value = draft.choices; custom.value = draft.custom }
} catch { /* A malformed tab-local draft cannot change the server question. */ }
watch([choices, custom], () => sessionStorage.setItem(draftKey, JSON.stringify({ choices: choices.value, custom: custom.value })), { deep: true })
const answers = computed(() => props.question.questions.map((q, i) => custom.value[i]?.trim() ? q.multiple ? [...choices.value[i]!, custom.value[i]!.trim()] : [custom.value[i]!.trim()] : choices.value[i]!))
async function submit() {
  if (busy.value || answers.value.some(a => !a.length)) return
  busy.value = true; error.value = ''
  try {
    const saved = sessionStorage.getItem(storage)
    const request = saved ? JSON.parse(saved) : { idempotencyKey: crypto.randomUUID(), version: props.question.version, answers: answers.value }
    if (saved && JSON.stringify(request.answers) !== JSON.stringify(answers.value)) throw new Error('上一条回答结果尚未核对，请先重试原回答或刷新对话')
    sessionStorage.setItem(storage, JSON.stringify(request))
    await knowledgeApi.reply(props.conversation, props.question.id, request); sessionStorage.removeItem(storage); sessionStorage.removeItem(draftKey); emit('answered')
  } catch (failure) { error.value = failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : '回答提交结果待核对，请重试原回答或刷新对话' }
  finally { busy.value = false }
}
function select(index: number, value: string, multiple: boolean) {
  if (multiple) choices.value[index] = choices.value[index]!.includes(value) ? choices.value[index]!.filter(x => x !== value) : [...choices.value[index]!, value]
  else { choices.value[index] = [value]; custom.value[index] = '' }
}
</script>
<template>
  <section class="knowledge-question" aria-label="助手提问">
    <header><Icon icon="lucide:message-circle-question" /><strong>{{ question.state === 'ANSWERED' ? '已回答' : question.state === 'CLOSED' ? '本轮已结束' : '需要你的回答' }}</strong></header>
    <form v-if="question.state === 'PENDING'" @submit.prevent="submit">
      <fieldset v-for="(prompt, index) in question.questions" :key="index" :disabled="busy"><legend>{{ prompt.question }}</legend>
        <label v-for="option in prompt.options" :key="option.label" class="knowledge-question-option"><input :type="prompt.multiple ? 'checkbox' : 'radio'" :name="`${id}-${index}`" :checked="choices[index]?.includes(option.label)" @change="select(index, option.label, prompt.multiple)"><span>{{ option.label }}<small>{{ option.description }}</small></span></label>
        <textarea v-if="prompt.custom" v-model="custom[index]" :aria-label="`问题 ${index + 1} 的自定义回答`" rows="2" maxlength="24000" placeholder="输入你的回答…" />
      </fieldset>
      <p v-if="error" role="alert" class="knowledge-notice">{{ error }}</p><button :disabled="busy || answers.some(a => !a.length)">{{ busy ? '正在提交…' : '提交回答并继续' }}</button>
    </form>
    <template v-else><div v-for="(prompt, index) in question.questions" :key="index"><p>{{ prompt.question }}</p><p v-if="question.answers[index]?.length" class="knowledge-question-answer">{{ question.answers[index]!.join('；') }}</p></div><p v-if="question.state === 'UNKNOWN'" role="status">回答投递结果待核对，未重复发送。可停止本轮后继续提问。</p><p v-else-if="['PREPARED', 'SENDING'].includes(question.state)" role="status">正在提交回答…</p></template>
  </section>
</template>
