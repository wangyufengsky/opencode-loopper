<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePptStore } from '@/stores/pptStore'
import type { PptScope, PptQuestion } from '@/types/domain'
import { pptRunLabel } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
const props = defineProps<{ scope: PptScope; scopeLabel: string; disabled?: boolean }>()
const emit = defineEmits<{ changeScope: [kind: PptScope['kind']] }>()
const store = usePptStore(), text = ref(''), answers = ref<Record<string, string>>({})
const canSend = computed(() => text.value.trim() && !store.active && !store.busy && !props.disabled && !store.pending)
const questions = computed(() => store.agent?.questions.filter(question => question.state === 'PENDING') || [])
let owner = ''
let pendingMessageKey = ''
function saveDraft() { if (owner) try { sessionStorage.setItem(`loopper.ppt.chat.${owner}`, text.value); sessionStorage.setItem(`loopper.ppt.answers.${owner}`, JSON.stringify(answers.value)) } catch { /* Draft is still present in the open page. */ } }
watch(() => store.document?.id, id => { saveDraft(); owner = id || ''; try { text.value = sessionStorage.getItem(`loopper.ppt.chat.${owner}`) || ''; answers.value = JSON.parse(sessionStorage.getItem(`loopper.ppt.answers.${owner}`) || '{}') as Record<string, string> } catch { text.value = ''; answers.value = {} } }, { immediate: true })
watch([text, answers], saveDraft, { deep: true })
watch([() => store.pending, () => store.messages], () => { if (store.pending?.kind === 'message') pendingMessageKey = store.pending.key; const accepted = store.messages.find(message => message.idempotencyKey === pendingMessageKey); if (accepted && accepted.text === text.value.trim()) { text.value = ''; pendingMessageKey = ''; saveDraft() } }, { deep: true })
async function send() { if (!canSend.value) return; const id = owner, value = text.value.trim(), scope = { ...props.scope }; if (await store.send(value, scope) && id === owner) { text.value = ''; saveDraft() } }
async function reply(question: PptQuestion) { const value = answers.value[question.id]?.trim(); if (!value) return; if (await store.reply(question, value)) { delete answers.value[question.id]; saveDraft() } }
</script>
<template>
  <section class="ppt-chat" aria-label="PPT 助手">
    <header class="ppt-section-heading"><strong>PPT 助手</strong><span>{{ pptRunLabel(store.agent?.state || 'IDLE') }}</span></header>
    <div class="ppt-chat-timeline" aria-live="polite">
      <button v-if="store.messageCursor" :disabled="store.busy" @click="store.more('messages')">更早的消息</button>
      <p v-if="!store.messages.length" class="ppt-muted">告诉我用途、受众与重点，我会先整理方向，再逐页制作。</p>
      <article v-for="message in store.messages" :key="message.id" class="ppt-chat-message"><div class="ppt-user-message"><strong>你</strong><p>{{ message.text }}</p><small>基于版本 {{ message.expectedRevision }}</small></div><div v-if="message.answer" class="ppt-agent-message"><strong>PPT 助手</strong><MarkdownDocument :content="message.answer" /></div><p v-if="message.detail" class="ppt-notice">{{ message.detail }}</p><details v-if="message.questions.some(question => question.state !== 'PENDING')"><summary>已处理的问题</summary><div v-for="question in message.questions.filter(question => question.state !== 'PENDING')" :key="question.id"><p>{{ question.prompt }}</p><p>{{ question.answer || '本轮已关闭' }}</p></div></details></article>
      <p v-if="store.active && !questions.length" role="status" class="ppt-muted">{{ pptRunLabel(store.agent?.state || '') }}</p>
      <p v-if="store.agent?.detail" class="ppt-notice">{{ store.agent.detail }}</p>
      <form v-for="question in questions" :key="question.id" class="ppt-question" @submit.prevent="reply(question)"><strong>{{ question.prompt }}</strong><fieldset :disabled="store.busy || store.agent?.state !== 'WAITING_INPUT'"><label v-for="option in question.options" :key="option" class="ppt-check"><input v-model="answers[question.id]" type="radio" :name="question.id" :value="option" />{{ option }}</label><label>你的回答<textarea v-model="answers[question.id]" rows="3" /></label><button :disabled="!answers[question.id]?.trim() || !!store.pending" class="ppt-primary">提交回答并继续</button></fieldset></form>
    </div>
    <form class="ppt-composer" @submit.prevent="send"><label>修改范围<select :value="scope.kind" @change="emit('changeScope', ($event.target as HTMLSelectElement).value as PptScope['kind'])"><option value="DOCUMENT">整份演示文稿</option><option value="SECTION">当前章节</option><option value="SLIDE">当前页面</option><option value="ELEMENT">选中对象</option></select><small>{{ scopeLabel }}</small></label><textarea v-model="text" aria-label="向 PPT 助手发送要求" rows="4" placeholder="例如：第二章减少技术细节，增加业务收益" :disabled="disabled" /><footer class="ppt-inline"><button v-if="store.active" type="button" :disabled="store.busy || store.agent?.state === 'STOPPING'" @click="store.stop">{{ store.agent?.state === 'STOPPING' ? '正在安全暂停' : '停止本轮' }}</button><button v-else class="ppt-primary" :disabled="!canSend">{{ store.busy ? '正在提交' : '发送' }}</button></footer></form>
  </section>
</template>
