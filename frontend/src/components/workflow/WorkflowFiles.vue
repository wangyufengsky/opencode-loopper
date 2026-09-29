<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import WorkflowDocumentPreview from './WorkflowDocumentPreview.vue'
import WorkflowCodeChanges from './WorkflowCodeChanges.vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowFile } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ requirement: string; node: string; attempt: string; direction: 'inputs' | 'outputs'; name: string; archive?: boolean; changes?: boolean }>()
const readable = (file: WorkflowFile) => !!file.sha256 || !!file.blobSha && !file.exclusion
const rows = ref<WorkflowFile[]>([]), cursor = ref<string | null>(null), busy = ref(false), loaded = ref(false), error = ref('')
const preview = ref('')
let alive = true
async function load(more = false) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { const page = await workflowRuns.files(props.requirement, props.node, props.attempt, props.direction, props.name, more ? cursor.value || '' : ''); if (alive) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null; loaded.value = true } }
  catch (failure) { if (alive) error.value = userFacingError(failure, '固定版本文件暂时无法读取，请重试。') }
  finally { if (alive) busy.value = false }
}
onBeforeUnmount(() => { alive = false })
</script>
<template><div class="workflow-file-list"><WorkflowCodeChanges v-if="changes" :requirement="requirement" :node="node" :attempt="attempt" :direction="direction" :name="name" /><p v-if="archive"><a :href="workflowRuns.archiveUrl(requirement, node, attempt, direction, name)" download>下载全部文档（ZIP）</a></p><button v-if="!loaded" :disabled="busy" @click="load()">{{ busy ? '读取文件…' : '查看固定版本文件' }}</button><p v-if="error" role="alert">{{ error }}<button @click="load()">重试</button></p><p v-if="loaded && !rows.length">该版本没有文件。</p><h5 v-if="changes && loaded">固定版本全部文件</h5><ul><li v-for="file in rows" :key="file.path"><a v-if="readable(file)" :href="workflowRuns.fileUrl(requirement, node, attempt, direction, name, file.path)" download>{{ file.path }}</a><span v-else>{{ file.path }}</span><button v-if="archive && readable(file) && file.path.endsWith('.md')" @click="preview = file.path">预览报告</button><small>{{ file.sizeBytes }} 字节<span v-if="file.target != null"> · {{ file.target ? '目标范围' : readable(file) ? '只读上下文' : '范围外' }}</span><span v-if="!readable(file)"> · 未采集正文</span></small><small v-if="file.exclusion">{{ file.exclusion }}</small></li></ul><button v-if="cursor" :disabled="busy" @click="load(true)">更多文件</button><WorkflowDocumentPreview v-if="preview" :requirement="requirement" :node="node" :attempt="attempt" :direction="direction" :name="name" :path="preview" @navigate="preview = $event" @close="preview = ''" /></div></template>
