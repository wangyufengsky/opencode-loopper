<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { PptDeck, PptJob } from '@/types/domain'
import { pptApi } from '@/api/ppt'

const props = defineProps<{
  documentId: string
  deck: PptDeck
  jobs: PptJob[]
  selected: string
  revision: number
  manual?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{
  select: [id: string]
  add: []
}>()
const previews = computed(() => {
  const result = new Map<string, string>()
  const jobs = props.jobs.filter((job) => job.kind === 'PREVIEW' && job.revision === props.revision)
  for (const job of jobs)
    for (const artifact of job.artifacts) {
      if (artifact.slideId && artifact.mediaType === 'image/png' && !result.has(artifact.slideId))
        result.set(artifact.slideId, pptApi.artifactUrl(props.documentId, artifact.id))
    }
  return result
})
</script>

<template>
  <nav class="ppt-page-rail" aria-label="演示页面">
    <button
      v-for="(slide, index) in deck.slides"
      :key="slide.id"
      :class="['ppt-page-thumbnail', { selected: selected === slide.id }]"
      :aria-label="`第 ${index + 1} 页：${slide.title}`"
      :aria-current="selected === slide.id ? 'page' : undefined"
      @click="emit('select', slide.id)"
    >
      <span class="ppt-thumbnail-image">
        <img v-if="previews.get(slide.id)" :src="previews.get(slide.id)" alt="" loading="lazy" />
        <span v-else class="ppt-thumbnail-title">{{ slide.title }}</span>
        <Icon
          v-if="slide.locked"
          class="ppt-thumbnail-lock"
          icon="lucide:lock"
          aria-label="已锁定"
        />
      </span>
      <span class="ppt-thumbnail-caption">
        <span>{{ String(index + 1).padStart(2, '0') }}</span>
        <span>{{ slide.title || '未命名页面' }}</span>
      </span>
    </button>
    <button v-if="manual" class="ppt-add-page" :disabled="disabled" @click="emit('add')">
      <Icon icon="lucide:plus" />
      新增页面
    </button>
  </nav>
</template>
