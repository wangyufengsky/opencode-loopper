<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import type { DocumentClarificationRequest, DocumentTemplateOverview } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ run: DocumentTemplateOverview; requirementKey: string }>()
const emit = defineEmits<{ updated: [run: DocumentTemplateOverview] }>()
const answer = ref(''); const loading = ref(false); const error = ref('')
let generation = 0
let pending: DocumentClarificationRequest | undefined
watch(() => [props.run.id, props.run.requirementRevision, props.requirementKey], () => {
  ++generation; answer.value = ''; error.value = ''; loading.value = false; pending = undefined
})
async function submit() {
  if (loading.value || !answer.value.trim()) return
  const text = answer.value.trim(); const current = generation
  if (!pending || pending.answers[0]?.answer !== text || pending.expectedVersion !== props.run.version)
    pending = { requestKey: crypto.randomUUID(), expectedVersion: props.run.version, requirementRevision: props.run.requirementRevision,
      answers: [{ requirementKey: props.requirementKey, answer: text }] }
  loading.value = true; error.value = ''
  try {
    const updated = await api.answerDocumentRequirements(props.run.id, pending)
    if (current === generation) { pending = undefined; answer.value = ''; emit('updated', updated) }
  } catch (failure) { if (current === generation) error.value = userFacingError(failure, '回答尚未确认，请读取最新状态后重试') }
  finally { if (current === generation) loading.value = false }
}
</script>
<template>
  <form class="clarification-form" aria-label="回答业务问题" @submit.prevent="submit">
    <label :for="`clarification-${requirementKey}`">补充业务规则或处理方式</label>
    <el-input :id="`clarification-${requirementKey}`" v-model="answer" type="textarea" :rows="3" :maxlength="4000" show-word-limit :disabled="loading" placeholder="请说明明确的业务选择及依据" />
    <p class="muted">回答后将重新整理并独立复核需求，旧版本保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button type="primary" native-type="submit" :loading="loading" :disabled="!answer.trim()">提交回答并复核</el-button>
  </form>
</template>
<style scoped>
.clarification-form { display: grid; gap: 12px; margin: 16px 0; }.clarification-form .el-button { justify-self: start; }.clarification-form p { margin: 0; }
</style>
