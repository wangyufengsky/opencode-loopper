<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import type { DocumentSupplementOptions, DocumentTemplateOverview } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ run: DocumentTemplateOverview }>()
const emit = defineEmits<{ updated: [DocumentTemplateOverview] }>()
const options = ref<DocumentSupplementOptions>()
const opened = ref(false)
const files = ref<File[]>([])
const busy = ref(false)
const error = ref('')
const invalidFiles = ref(false)
let generation = 0
watch(() => props.run.id, () => { ++generation; options.value = undefined; opened.value = false; files.value = []; error.value = ''; invalidFiles.value = false; busy.value = false })
async function open() {
  if (busy.value) return
  const token = generation; busy.value = true; error.value = ''
  try { const value = await api.documentSupplementOptions(props.run.id); if (token === generation) { options.value = value; opened.value = true } }
  catch (failure) { if (token === generation) error.value = userFacingError(failure, '补充入口读取失败，请重试') }
  finally { if (token === generation) busy.value = false }
}
function choose(event: Event) {
  files.value = Array.from((event.target as HTMLInputElement).files ?? [])
  error.value = ''
  if (files.value.some(file => !/\.(docx|md|markdown|pdf)$/i.test(file.name))) error.value = '仅支持 DOCX、Markdown 和文本 PDF'
  else if (files.value.length > 10 || files.value.some(file => !file.size || file.size > 20 * 1024 * 1024)
      || files.value.reduce((sum, file) => sum + file.size, 0) > 50 * 1024 * 1024)
    error.value = '原文与补充文档合计最多 10 份、50 MiB，单文件最多 20 MiB'
  invalidFiles.value = !!error.value
}
async function submit() {
  if (busy.value || !options.value?.request || !files.value.length || invalidFiles.value) return
  const token = generation; busy.value = true; error.value = ''
  try {
    const value = await api.uploadDocumentSupplement(props.run.id, options.value.request, files.value)
    if (token === generation) { emit('updated', value); opened.value = false; files.value = []; options.value = undefined }
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '补充上传未确认，可使用相同文件重试') }
  finally { if (token === generation) busy.value = false }
}
</script>
<template>
  <section class="card card-pad supplement" aria-label="补充需求文档">
    <el-button v-if="!opened" :loading="busy" @click="open">补充需求文档</el-button>
    <template v-else>
      <h2>补充需求文档</h2><p class="muted">{{ options?.message }}</p>
      <template v-if="options?.available">
        <label>选择文档<input type="file" multiple accept=".docx,.md,.markdown,.pdf" :disabled="busy" @change="choose"></label>
        <el-button type="primary" :loading="busy" :disabled="!files.length || invalidFiles" @click="submit">上传并重新复核需求</el-button>
      </template>
      <el-button :disabled="busy" @click="opened = false; error = ''">收起</el-button>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<style scoped>
.supplement { display: grid; gap: 12px; justify-items: start; }h2 { margin: 0; font-size: 18px; }label { display: grid; gap: 8px; width: 100%; }input { max-width: 100%; }.muted { margin: 0; }
</style>
