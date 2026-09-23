<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { pptApi } from '@/api/ppt'
import { pptPhaseLabel } from '@/utils/displayLabels'
import type { PptDocument } from '@/types/domain'
import PptPromptInput from '@/components/ppt/PptPromptInput.vue'
import { usePptCreation } from '@/components/ppt/usePptCreation'
import '@/components/ppt/ppt.css'

const router = useRouter()
const creation = usePptCreation()
const rows = ref<PptDocument[]>([])
const cursor = ref<string | null>(null)
const query = ref('')
const archived = ref(false)
const loading = ref(false)
const starting = ref(false)
const error = ref('')
let sequence = 0

async function load(more = false) {
  const ticket = ++sequence
  loading.value = true
  error.value = ''
  try {
    const page = await pptApi.list({
      query: query.value || undefined,
      archived: archived.value,
      cursor: more ? cursor.value || '' : '',
    })
    if (ticket === sequence) {
      rows.value = more ? [...rows.value, ...page.items] : page.items
      cursor.value = page.nextCursor ?? null
    }
  } catch {
    if (ticket === sequence) error.value = '作品暂时无法读取，请重试。'
  } finally {
    if (ticket === sequence) loading.value = false
  }
}

async function startDiscussion() {
  if (starting.value) return
  const request = await creation.prepare()
  if (!request) return
  starting.value = true
  try {
    await pptApi.send(request.id, {
      idempotencyKey: request.key,
      expectedRevision: request.revision,
      text: request.prompt,
      scope: { kind: 'DOCUMENT' },
    })
    creation.accepted()
    await router.push(`/ppt/${request.id}`)
  } catch (failure) {
    creation.error.value =
      failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message)
        ? failure.message
        : '暂时未收到回复。请重试原要求，我们会核对同一份作品。'
  } finally {
    starting.value = false
  }
}

function updatedAt(value: string) {
  return new Date(value).toLocaleDateString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
  })
}

onMounted(() => {
  void load()
})
function toggleArchive() {
  archived.value = !archived.value
  void load()
}
</script>

<template>
  <main id="main-content" class="ppt-page ppt-library" aria-label="PPT 制作">
    <header class="ppt-library-brand">
      <Icon icon="lucide:presentation" />
      <span>PPT 工作室</span>
    </header>
    <section class="ppt-hero" aria-labelledby="ppt-hero-title">
      <div class="ppt-hero-mark" aria-hidden="true">
        <Icon icon="lucide:sparkles" />
      </div>
      <h1 id="ppt-hero-title">
        从一个想法，
        <br class="ppt-mobile-break" />
        到一份好演示。
      </h1>
      <p>说出你的想法，和 PPT 助手一起把要求聊清楚。</p>
      <PptPromptInput
        v-model="creation.prompt.value"
        v-model:project="creation.project.value"
        :files="creation.files.value"
        :busy="creation.busy.value || starting"
        :locked="creation.locked.value"
        :detail="creation.detail.value"
        :error="creation.error.value"
        @files="creation.addFiles"
        @remove="creation.removeFile"
        @submit="startDiscussion"
      />
      <div class="ppt-hero-footnote">
        <span>
          <Icon icon="lucide:message-circle" />
          随时提意见，随时修改
        </span>
        <span>
          <Icon icon="lucide:file-down" />
          下载后继续编辑
        </span>
      </div>
      <RouterLink
        v-if="creation.documentId.value"
        :to="`/ppt/${creation.documentId.value}`"
        class="ppt-recovery-link"
      >
        打开已创建的作品
        <Icon icon="lucide:arrow-right" />
      </RouterLink>
    </section>

    <section class="ppt-recent" aria-labelledby="ppt-recent-title">
      <header class="ppt-recent-header">
        <div>
          <h2 id="ppt-recent-title">
            {{ archived ? '已归档作品' : '最近作品' }}
          </h2>
          <span v-if="rows.length">继续上次的想法</span>
        </div>
        <form class="ppt-library-filters" @submit.prevent="load()">
          <label class="ppt-search">
            <Icon icon="lucide:search" />
            <input v-model="query" aria-label="搜索作品" placeholder="搜索作品" />
          </label>
          <button
            type="button"
            :class="{ active: archived }"
            :aria-pressed="archived"
            @click="toggleArchive"
          >
            {{ archived ? '返回最近' : '归档' }}
          </button>
        </form>
      </header>
      <p v-if="error" role="alert" class="ppt-notice">
        {{ error }}
        <button @click="load()">重试</button>
      </p>
      <p v-if="loading && !rows.length" role="status" class="ppt-empty">正在读取作品…</p>
      <div v-else-if="!rows.length" class="ppt-library-empty">
        <Icon icon="lucide:files" />
        <p>
          {{ query || archived ? '这里还没有符合条件的作品。' : '你的演示作品会出现在这里。' }}
        </p>
      </div>
      <div class="ppt-document-grid" aria-label="作品列表">
        <RouterLink
          v-for="document in rows"
          :key="document.id"
          :to="`/ppt/${document.id}`"
          class="ppt-document-card"
        >
          <div class="ppt-document-symbol" aria-hidden="true">
            <Icon icon="lucide:presentation" />
          </div>
          <div class="ppt-document-info">
            <h3>{{ document.title }}</h3>
            <p>
              <span>{{ pptPhaseLabel(document.phase) }}</span>
              <span>{{ updatedAt(document.updatedAt) }}</span>
            </p>
          </div>
          <Icon class="ppt-document-arrow" icon="lucide:arrow-up-right" aria-hidden="true" />
        </RouterLink>
      </div>
      <button v-if="cursor" class="ppt-load-more" :disabled="loading" @click="load(true)">
        更多作品
        <Icon icon="lucide:chevron-down" />
      </button>
    </section>
  </main>
</template>
