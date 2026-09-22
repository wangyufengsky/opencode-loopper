<script setup lang="ts">
import { usePptStore } from '@/stores/pptStore'
import PptArtifactDownload from './PptArtifactDownload.vue'
import { pptJobLabel } from '@/utils/displayLabels'
const store = usePptStore()
const emit = defineEmits<{ view: [revision: number]; restore: [revision: number] }>()
</script>
<template>
  <section class="ppt-history" aria-label="版本与导出">
    <h2>制作与导出</h2><p v-if="!store.jobs.length" class="ppt-empty">暂无制作记录</p>
    <article v-for="job in store.jobs" :key="job.id" class="ppt-history-item"><header class="ppt-section-heading"><strong>{{ job.kind === 'EXPORT' ? 'PPTX 导出' : '页面预览' }}</strong><span>{{ pptJobLabel(job.state) }}</span></header><p>版本 {{ job.revision }}<span v-if="job.revision !== store.document?.revision"> · 当前草稿已有更新</span></p><p v-if="job.total">已生成 {{ job.completed }} / {{ job.total }} 页</p><progress v-if="job.total && ['PENDING', 'PREPARED', 'QUEUED', 'RUNNING'].includes(job.state)" :value="job.completed" :max="job.total" /><p v-if="job.detail" class="ppt-notice">{{ job.detail }}</p><div v-if="store.document" class="ppt-downloads"><PptArtifactDownload v-for="artifact in job.artifacts" :key="artifact.id" :document-id="store.document.id" :artifact="artifact" :label="artifact.slideId ? `${store.deck?.slides.find(slide => slide.id === artifact.slideId)?.title || '页面'}预览.png` : undefined" /></div><button v-if="job.state === 'FAILED'" :disabled="store.busy" @click="store.retryJob(job.id)">继续生成未完成页面</button></article>
    <h2>历史版本</h2><article v-for="revision in store.revisions" :key="revision.revision" class="ppt-history-item"><strong>版本 {{ revision.revision }}</strong><p>{{ revision.reason || '保存修改' }} · {{ new Date(revision.createdAt).toLocaleString('zh-CN') }}</p><div class="ppt-inline"><button @click="emit('view', revision.revision)">查看</button><button :disabled="store.busy || store.active || revision.revision === store.document?.revision" @click="emit('restore', revision.revision)">恢复为新版本</button></div></article><button v-if="store.revisionCursor" @click="store.more('revisions')">更多版本</button>
  </section>
</template>
