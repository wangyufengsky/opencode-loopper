<script setup lang="ts">
import { ref } from 'vue'
import { pptApi } from '@/api/ppt'
import { usePptStore } from '@/stores/pptStore'
import type { PptSourceContent } from '@/types/domain'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import PptProjectSources from './PptProjectSources.vue'
withDefaults(
  defineProps<{
    allowInsert?: boolean
  }>(),
  {
    allowInsert: true,
  },
)
const emit = defineEmits<{
  insert: [assetId: string]
}>()
const store = usePptStore(),
  busy = ref(false),
  error = ref(''),
  content = ref<PptSourceContent | null>(null),
  failed = ref<{
    file: File
    kind: 'sources' | 'assets'
    key: string
  } | null>(null)
async function upload(file: File, kind: 'sources' | 'assets', key?: string) {
  if (!store.document || busy.value) return
  if (file.size > 20 * 1024 * 1024) {
    error.value = '单个文件不能超过 20 MiB，请压缩后重新选择'
    return
  }
  const id = store.document.id
  busy.value = true
  error.value = ''
  let receiptKey = ''
  try {
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    const hash = [...new Uint8Array(digest)]
      .map((value) => value.toString(16).padStart(2, '0'))
      .join('')
    receiptKey = `loopper.ppt.upload.${id}.${kind}.${file.name}.${hash}`
    key ||= sessionStorage.getItem(receiptKey) || crypto.randomUUID()
    sessionStorage.setItem(receiptKey, key)
  } catch {
    key ||= crypto.randomUUID()
  }
  try {
    const result = await pptApi.upload(id, file, kind, key!)
    if (result.state === 'READY' && receiptKey)
      try {
        sessionStorage.removeItem(receiptKey)
      } catch {
        /* Upload already accepted. */
      }
    failed.value =
      result.state === 'READY'
        ? null
        : {
            file,
            kind,
            key: key!,
          }
    if (id === store.document?.id) {
      if (result.state !== 'READY') error.value = result.detail || '文件尚未就绪，请重试原文件'
      await store.refresh()
    }
  } catch (failure) {
    if (id === store.document?.id) {
      error.value =
        failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message)
          ? failure.message
          : '上传结果待核对，请重试原文件'
      failed.value = {
        file,
        kind,
        key: key!,
      }
    }
  } finally {
    busy.value = false
  }
}
async function choose(event: Event, kind: 'sources' | 'assets') {
  const input = event.target as HTMLInputElement
  const files = [...(input.files || [])]
  for (const file of files) {
    await upload(file, kind)
    if (failed.value) break
  }
  input.value = ''
}
async function read(sourceId: string) {
  if (!store.document) return
  const id = store.document.id
  error.value = ''
  try {
    const result = await pptApi.source(id, sourceId)
    if (id === store.document?.id) content.value = result
  } catch {
    error.value = '资料暂时无法读取，请重新打开'
  }
}
async function drop(event: DragEvent) {
  event.preventDefault()
  if (busy.value || store.active || store.document?.archived) return
  for (const file of [...(event.dataTransfer?.files || [])]) {
    await upload(file, /^image\//.test(file.type) ? 'assets' : 'sources')
    if (failed.value) break
  }
}
</script>
<template>
  <section class="ppt-sources" aria-label="资料与素材" @dragover.prevent @drop="drop">
    <PptProjectSources v-if="store.document?.projectId" :document-id="store.document.id" />
    <p class="ppt-muted">添加文字资料与图片，也可以拖入文件。</p>
    <div class="ppt-upload-actions">
      <label class="ppt-upload">
        添加资料
        <input
          type="file"
          accept=".md,.docx,.xlsx,.pptx,.pdf"
          multiple
          :disabled="busy || store.active || store.document?.archived"
          @change="choose($event, 'sources')"
        />
      </label>
      <label class="ppt-upload">
        添加图片
        <input
          type="file"
          accept="image/png,image/jpeg"
          multiple
          :disabled="busy || store.active || store.document?.archived"
          @change="choose($event, 'assets')"
        />
      </label>
    </div>
    <small class="ppt-muted">
      资料最多 10 份，每份 20 MiB，总计 50 MiB。已有 PPT 将提取内容后重新制作。
    </small>
    <p v-if="busy" role="status">正在上传与解析…</p>
    <p v-if="error" role="alert" class="ppt-notice">
      {{ error }}
      <button v-if="failed" :disabled="busy" @click="upload(failed.file, failed.kind, failed.key)">
        重试原文件
      </button>
    </p>
    <h3>资料</h3>
    <p v-if="!store.sources.length" class="ppt-empty">暂无资料</p>
    <article v-for="source in store.sources" :key="source.id" class="ppt-resource">
      <button @click="read(source.id)">{{ source.name }}</button>
      <small>{{ (source.bytes / 1024).toFixed(0) }} KiB · {{ source.sections }} 个片段</small>
      <p v-if="source.detail">{{ source.detail }}</p>
      <p v-for="limitation in source.limitations" :key="limitation" class="ppt-muted">
        {{ limitation }}
      </p>
    </article>
    <h3>图片素材</h3>
    <p v-if="!store.assets.length" class="ppt-empty">暂无图片</p>
    <article v-for="asset in store.assets" :key="asset.id" class="ppt-resource">
      <img
        v-if="store.document"
        :src="pptApi.assetUrl(store.document.id, asset.id)"
        :alt="asset.name"
        loading="lazy"
      />
      <span>{{ asset.name }}</span>
      <button
        v-if="allowInsert"
        :disabled="!store.editable || store.active"
        @click="emit('insert', asset.id)"
      >
        插入当前页
      </button>
    </article>
    <section v-if="content" class="ppt-source-body">
      <header class="ppt-section-heading">
        <strong>{{ content.name }}</strong>
        <button @click="content = null">关闭正文</button>
      </header>
      <details v-for="section in content.sections" :key="section.id">
        <summary>{{ section.title || '资料片段' }}</summary>
        <MarkdownDocument :content="section.markdown" />
      </details>
    </section>
  </section>
</template>
