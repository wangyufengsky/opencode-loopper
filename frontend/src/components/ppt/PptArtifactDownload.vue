<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { pptApi } from '@/api/ppt'
import type { PptArtifact } from '@/types/domain'
const props = defineProps<{ documentId: string; artifact: PptArtifact; label?: string }>()
const busy = ref(false), error = ref('')
let controller: AbortController | undefined
const urls = new Set<string>()
async function download() {
  if (busy.value) return
  busy.value = true; error.value = ''; controller = new AbortController()
  try {
    const response = await fetch(pptApi.artifactUrl(props.documentId, props.artifact.id), { signal: controller.signal })
    if (!response.ok) throw new Error('下载失败')
    const blob = await response.blob()
    if (!blob.size) throw new Error('文件为空')
    const url = URL.createObjectURL(blob); urls.add(url)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = props.label || props.artifact.name; document.body.appendChild(anchor); anchor.click(); anchor.remove()
    window.setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url) }, 30_000)
  } catch (cause) { if (!(cause instanceof DOMException && cause.name === 'AbortError')) error.value = '文件暂时无法下载，请重试。已有导出仍会保留。' }
  finally { busy.value = false }
}
onBeforeUnmount(() => { controller?.abort(); for (const url of urls) URL.revokeObjectURL(url) })
</script>
<template>
  <span><a :href="pptApi.artifactUrl(documentId, artifact.id)" :download="label || artifact.name" :aria-busy="busy" @click.prevent="download">{{ label || artifact.name }}</a><small v-if="busy"> · 正在下载</small><span v-if="error" role="alert" class="ppt-notice">{{ error }} <button @click="download">重试下载</button></span></span>
</template>
