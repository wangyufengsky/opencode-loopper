<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { usePptStore } from '@/stores/pptStore'
import type { PptScope, PptQuestion, PptMessage } from '@/types/domain'
import MarkdownDocument from '@/components/MarkdownDocument.vue'

const props = withDefaults(
  defineProps<{
    scope: PptScope
    scopeLabel: string
    disabled?: boolean
    ready?: boolean
    spacious?: boolean
  }>(),
  {
    ready: true,
  },
)
const emit = defineEmits<{
  changeScope: [kind: PptScope['kind']]
}>()
const store = usePptStore()
const text = ref('')
const answers = ref<Record<string, string>>({})
const showHistory = ref(false)
const timeline = ref<HTMLElement>()
const followLatest = ref(true)
const unseen = ref(false)
const questions = computed(
  () => store.agent?.questions.filter((question) => question.state === 'PENDING') || [],
)
const canSend = computed(
  () =>
    text.value.trim() &&
    !store.active &&
    !store.busy &&
    !props.disabled &&
    !store.pending &&
    !['STOPPED', 'FAILED'].includes(store.generation?.state || '') &&
    (props.ready || !store.generation),
)
const displayedMessages = computed(() =>
  showHistory.value ? store.messages : store.messages.slice(-3),
)
function scrollLatest() {
  if (timeline.value && !props.spacious) timeline.value.scrollTop = timeline.value.scrollHeight
  followLatest.value = true
  unseen.value = false
}
function onTimelineScroll() {
  if (!timeline.value || props.spacious) return
  followLatest.value =
    timeline.value.scrollHeight - timeline.value.clientHeight - timeline.value.scrollTop < 80
  if (followLatest.value) unseen.value = false
}
function showPrevious() {
  followLatest.value = false
  showHistory.value = true
}
function foldReply(message: PptMessage) {
  return message.answer.length > 600 && message.state !== 'FAILED'
}
function replyPreview(answer: string) {
  const paragraphs = answer.trim().split(/\n\s*\n/)
  const ending = paragraphs[paragraphs.length - 1] || answer
  return ending.length <= 300 ? ending : `${ending.slice(0, 300)}…`
}
watch(
  () =>
    JSON.stringify([
      displayedMessages.value.map((message) => [message.id, message.answer, message.detail]),
      questions.value,
    ]),
  async () => {
    if (props.spacious) return
    await nextTick()
    if (followLatest.value) scrollLatest()
    else unseen.value = true
  },
)
onMounted(() => {
  scrollLatest()
})
let owner = ''
let pendingMessageKey = ''

function saveDraft() {
  if (!owner) return
  try {
    sessionStorage.setItem(`loopper.ppt.chat.${owner}`, text.value)
    sessionStorage.setItem(`loopper.ppt.answers.${owner}`, JSON.stringify(answers.value))
  } catch {
    /* Draft remains in the open page. */
  }
}

watch(
  () => store.document?.id,
  (id) => {
    saveDraft()
    owner = id || ''
    try {
      text.value = sessionStorage.getItem(`loopper.ppt.chat.${owner}`) || ''
      answers.value = JSON.parse(
        sessionStorage.getItem(`loopper.ppt.answers.${owner}`) || '{}',
      ) as Record<string, string>
    } catch {
      text.value = ''
      answers.value = {}
    }
  },
  {
    immediate: true,
  },
)
watch([text, answers], saveDraft, {
  deep: true,
})
watch(
  [() => store.pending, () => store.messages],
  () => {
    if (store.pending?.kind === 'message') pendingMessageKey = store.pending.key
    const accepted = store.messages.find((message) => message.idempotencyKey === pendingMessageKey)
    if (accepted && accepted.text === text.value.trim()) {
      text.value = ''
      pendingMessageKey = ''
      saveDraft()
    }
  },
  {
    deep: true,
  },
)

async function send() {
  if (!canSend.value) return
  const id = owner
  const value = text.value.trim()
  const accepted = props.ready
    ? await store.send(value, {
        ...props.scope,
      })
    : await store.generate(value)
  if (accepted && id === owner) {
    text.value = ''
    saveDraft()
  }
}

async function reply(question: PptQuestion) {
  const value = answers.value[question.id]?.trim()
  if (value && (await store.reply(question, value))) {
    delete answers.value[question.id]
    saveDraft()
  }
}
</script>

<template>
  <section class="ppt-chat" :class="{ spacious }" aria-label="PPT 助手">
    <header v-if="!spacious" class="ppt-chat-heading">
      <span class="ppt-assistant-icon">
        <Icon icon="lucide:sparkles" />
      </span>
      <div>
        <h2>PPT 助手</h2>
        <p>说出你的想法，我来调整。</p>
      </div>
    </header>
    <div ref="timeline" class="ppt-chat-timeline" @scroll="onTimelineScroll">
      <button
        v-if="store.messages.length > 3 && !showHistory"
        class="ppt-history-toggle"
        @click="showPrevious"
      >
        查看之前的对话
        <Icon icon="lucide:chevron-down" />
      </button>
      <button
        v-if="showHistory && store.messageCursor"
        :disabled="store.busy"
        @click="store.more('messages')"
      >
        更早的消息
      </button>
      <div v-if="!store.messages.length && ready && !questions.length" class="ppt-chat-welcome">
        <Icon icon="lucide:message-square-text" />
        <h3>哪里需要再打磨？</h3>
        <p>
          “再精简一点”
          <br />
          “突出第二页的结论”
          <br />
          “换成更稳重的风格”
        </p>
      </div>
      <article
        v-for="(message, index) in displayedMessages"
        :key="message.id"
        class="ppt-chat-message"
      >
        <div
          v-if="index === 0 || displayedMessages[index - 1]?.text !== message.text"
          class="ppt-user-message"
        >
          <p>{{ message.text }}</p>
        </div>
        <div v-if="message.answer" class="ppt-agent-message">
          <template v-if="foldReply(message)">
            <MarkdownDocument :content="replyPreview(message.answer)" />
            <details class="ppt-long-reply" @toggle="onTimelineScroll">
              <summary>展开完整回复</summary>
              <MarkdownDocument :content="message.answer" />
            </details>
          </template>
          <MarkdownDocument v-else :content="message.answer" />
        </div>
        <p
          v-if="message.detail && (!store.generation || message.state !== 'COMPLETED')"
          class="ppt-notice"
        >
          {{ message.detail }}
        </p>
        <details v-if="message.questions.some((question) => question.state !== 'PENDING')">
          <summary>已补充的信息</summary>
          <div
            v-for="question in message.questions.filter((question) => question.state !== 'PENDING')"
            :key="question.id"
          >
            <p>{{ question.prompt }}</p>
            <p>{{ question.answer || '本轮已关闭' }}</p>
          </div>
        </details>
      </article>
      <p
        v-if="
          store.agent?.detail &&
          !store.generation?.detail &&
          (!store.generation || store.agent.state !== 'COMPLETED')
        "
        class="ppt-notice"
      >
        {{ store.agent.detail }}
      </p>
      <form
        v-for="question in questions"
        :key="question.id"
        class="ppt-question"
        @submit.prevent="reply(question)"
      >
        <span class="ppt-question-kicker">
          <Icon icon="lucide:message-circle-question" />
          需要你补充
        </span>
        <h3>{{ question.prompt }}</h3>
        <fieldset :disabled="store.busy || store.agent?.state !== 'WAITING_INPUT'">
          <label v-for="option in question.options" :key="option" class="ppt-question-option">
            <input
              v-model="answers[question.id]"
              type="radio"
              :name="question.id"
              :value="option"
            />
            {{ option }}
          </label>
          <label class="ppt-sr-only" :for="`answer-${question.id}`">你的回答</label>
          <textarea
            :id="`answer-${question.id}`"
            v-model="answers[question.id]"
            rows="2"
            placeholder="也可以直接补充你的想法"
          />
          <button :disabled="!answers[question.id]?.trim() || !!store.pending" class="ppt-primary">
            回答并继续
            <Icon icon="lucide:arrow-right" />
          </button>
        </fieldset>
      </form>
    </div>
    <button v-if="unseen && !spacious" class="ppt-return-latest" @click="scrollLatest">
      <Icon icon="lucide:arrow-down" />
      {{ questions.length ? '有信息需要你补充' : '回到最新回复' }}
    </button>
    <div v-if="questions.length && store.active" class="ppt-question-controls">
      <button
        type="button"
        :disabled="
          store.busy || store.agent?.state === 'STOPPING' || store.generation?.state === 'STOPPING'
        "
        @click="store.stop"
      >
        <Icon icon="lucide:square" />
        暂停制作
      </button>
    </div>
    <form v-else class="ppt-composer" @submit.prevent="send">
      <div v-if="ready" class="ppt-scope-chip">
        <Icon :icon="scope.kind === 'DOCUMENT' ? 'lucide:layers' : 'lucide:focus'" />
        <span>{{ scopeLabel }}</span>
        <button
          v-if="scope.kind !== 'DOCUMENT'"
          type="button"
          aria-label="改为修改整份演示文稿"
          @click="emit('changeScope', 'DOCUMENT')"
        >
          <Icon icon="lucide:x" />
        </button>
      </div>
      <textarea
        maxlength="24000"
        v-model="text"
        aria-label="向 PPT 助手发送要求"
        rows="3"
        :placeholder="ready ? '告诉我怎么改…' : '补充你的要求，或告诉我从哪里开始…'"
        :disabled="disabled"
        @keydown.meta.enter.prevent="send"
        @keydown.ctrl.enter.prevent="send"
      />
      <footer>
        <span v-if="store.active" class="ppt-composer-status">
          <span class="ppt-status-dot" />
          {{
            store.agent?.state === 'WAITING_INPUT'
              ? '等待你的回答'
              : store.agent?.state === 'STOPPING' || store.generation?.state === 'STOPPING'
                ? '正在暂停'
                : '助手正在处理'
          }}
        </span>
        <span v-else class="ppt-composer-hint">
          {{ ready ? '想改哪里，直接告诉我' : '制作会自动继续' }}
        </span>
        <button
          v-if="store.active"
          type="button"
          class="ppt-stop-button"
          :disabled="
            store.busy ||
            store.agent?.state === 'STOPPING' ||
            store.generation?.state === 'STOPPING'
          "
          @click="store.stop"
        >
          <Icon icon="lucide:square" />
          暂停
        </button>
        <button v-else class="ppt-primary" :disabled="!canSend">
          <Icon icon="lucide:arrow-up" />
          {{ ready ? '修改' : '生成 PPT' }}
        </button>
      </footer>
    </form>
  </section>
</template>
