<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { pptApi } from '@/api/ppt'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import type { PptKnowledge } from '@/types/ppt'

const props = defineProps<{ documentId: string }>()
const knowledge = ref<PptKnowledge | null>(null)
const busy = ref(false)
const error = ref('')
let sequence = 0
async function load() {
  const ticket = ++sequence
  busy.value = true
  error.value = ''
  try {
    const result = await pptApi.knowledge(props.documentId)
    if (ticket === sequence) knowledge.value = result
  } catch {
    if (ticket === sequence) error.value = '项目来源暂时无法读取，请重试。'
  } finally {
    if (ticket === sequence) busy.value = false
  }
}
watch(() => props.documentId, () => { knowledge.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <section class="ppt-project-sources" aria-label="关联项目来源">
    <h3><Icon icon="lucide:folder-open" /> {{ knowledge?.project?.name || '项目知识库' }}</h3>
    <p v-if="busy" role="status" class="ppt-muted">正在读取项目来源…</p>
    <p v-if="error" role="alert" class="ppt-notice">{{ error }} <button :disabled="busy" @click="load">重试来源</button></p>
    <template v-if="knowledge">
      <p v-if="knowledge.detail" class="ppt-muted">{{ knowledge.detail }}</p>
      <p v-else-if="knowledge.project" class="ppt-muted">助手可按需检索以下来源，为这份演示取材。</p>
      <p v-else class="ppt-muted">未关联项目，助手使用当前作品中的资料。</p>
      <ul v-if="knowledge.sources.length" class="ppt-project-source-list">
        <li v-for="source in knowledge.sources" :key="source.id">
          <div><span>{{ source.name }}</span><small :class="{ available: source.state === 'READY' }">{{ knowledgeStateLabel(source.state) }}</small></div>
          <p v-if="source.detail">{{ source.detail }}</p>
        </li>
      </ul>
      <p v-else-if="knowledge.project && !knowledge.detail" class="ppt-muted">此作品没有可用的项目来源，你仍可上传资料并继续沟通。</p>
    </template>
  </section>
</template>
