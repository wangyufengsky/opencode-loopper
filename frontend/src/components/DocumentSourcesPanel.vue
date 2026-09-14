<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import type { DocumentTemplateOverview, DocumentSectionPage, DocumentSection } from '@/types/domain'
import MarkdownDocument from './MarkdownDocument.vue'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ run: DocumentTemplateOverview }>()
const pages = ref<Record<string, DocumentSectionPage>>({})
const bodies = ref<Record<string, DocumentSection>>({})
const busy = ref<Record<string, boolean>>({})
const error = ref('')
let generation = 0
watch(() => `${props.run.id}:${props.run.sourceRevision ?? 0}`, () => {
  ++generation; pages.value = {}; bodies.value = {}; busy.value = {}; error.value = ''
}, { immediate: true })
async function sections(fileId: string, next = false) {
  const key = `index:${fileId}`; const previous = pages.value[fileId]
  if (busy.value[key] || previous && !next || next && previous?.nextOffset == null) return
  const token = generation; busy.value[key] = true; error.value = ''
  try {
    const page = await api.documentSections(props.run.id, fileId, next ? previous!.nextOffset! : 0)
    if (token === generation) pages.value[fileId] = { items: [...(next ? previous!.items : []), ...page.items], nextOffset: page.nextOffset }
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '原文目录读取失败，请重试') }
  finally { if (token === generation) busy.value[key] = false }
}
async function body(file: DocumentTemplateOverview['files'][number], ordinal: number) {
  const key = `${file.id}:${ordinal}`
  if (busy.value[key] || bodies.value[key]) return
  const token = generation; busy.value[key] = true; error.value = ''
  try {
    const section = await api.documentSection(props.run.id, file.id, ordinal, file.sha256)
    if (token === generation) bodies.value[key] = section
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '原文读取失败，请重试') }
  finally { if (token === generation) busy.value[key] = false }
}
</script>
<template>
  <section class="card card-pad" aria-label="冻结原文">
    <h2>原文目录</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <details v-for="file in run.files" :key="file.id" @toggle="($event.target as HTMLDetailsElement).open && sections(file.id)">
      <summary>{{ file.filename }} · {{ file.sectionCount }} 个分段</summary>
      <el-button v-if="!pages[file.id]" :loading="busy[`index:${file.id}`]" @click="sections(file.id)">读取目录</el-button>
      <details v-for="item in pages[file.id]?.items ?? []" :key="item.ordinal" @toggle="($event.target as HTMLDetailsElement).open && body(file, item.ordinal)">
        <summary>{{ item.title || `第 ${item.ordinal + 1} 段` }}</summary>
        <MarkdownDocument v-if="bodies[`${file.id}:${item.ordinal}`]" :content="bodies[`${file.id}:${item.ordinal}`]!.content" />
        <el-button v-else :loading="busy[`${file.id}:${item.ordinal}`]" @click="body(file, item.ordinal)">读取原文</el-button>
      </details>
      <el-button v-if="pages[file.id]?.nextOffset != null" :loading="busy[`index:${file.id}`]" @click="sections(file.id, true)">更多章节</el-button>
    </details>
  </section>
</template>
<style scoped>
h2 { margin: 0 0 12px; font-size: 18px; } details { margin: 12px 0; overflow-wrap: anywhere; } details details { margin-left: 16px; } summary { cursor: pointer; line-height: 1.8; }
</style>
