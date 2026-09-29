<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { TemplateBranchChoice } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { repositoryBranchLabel } from './repository'
const props = defineProps<{ project: string; value: string; title: string; disabled?: boolean }>()
const emit = defineEmits<{ change: [value: string] }>()
const rows = ref<TemplateBranchChoice[]>([]), cursor = ref<string | null>(null), query = ref(''), error = ref(''), problems = ref<string[]>([])
const busy = ref(false), opened = ref(false), loaded = ref(false)
let generation = 0
async function search(more = false) {
  if (props.disabled || !props.project) return
  const ticket = ++generation, project = props.project
  busy.value = true; error.value = ''; opened.value = true
  if (!more) { rows.value = []; cursor.value = null; loaded.value = false }
  try {
    const result = await api.templateBranches(project, query.value.trim(), more ? cursor.value || undefined : undefined)
    if (ticket !== generation || project !== props.project) return
    rows.value = more ? [...rows.value, ...result.page.items] : result.page.items
    cursor.value = result.page.nextCursor; problems.value = result.remoteProblems || []; loaded.value = true
  } catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '分支无法读取，请检查项目仓库和连接后重试。') }
  finally { if (ticket === generation) busy.value = false }
}
function select(value: string) { if (!props.disabled && !busy.value && rows.value.some(row => row.id === value)) emit('change', value) }
watch(() => props.project, () => { generation++; rows.value = []; cursor.value = null; query.value = ''; error.value = ''; problems.value = []; busy.value = false; opened.value = false; loaded.value = false })
onBeforeUnmount(() => { generation++ })
</script>
<template><section class="workflow-fields" :aria-label="title">
  <p>{{ repositoryBranchLabel(value) }}</p>
  <button type="button" :disabled="disabled || busy" @click="search()">{{ opened ? '刷新分支' : '选择代码分支' }}</button>
  <template v-if="opened">
    <label>搜索分支<input v-model="query" :disabled="disabled" @keydown.enter.prevent="search()" /></label>
    <button type="button" :disabled="disabled || busy" @click="search()">搜索</button>
    <p v-if="busy" role="status">正在读取分支…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-for="problem in problems" :key="problem" role="status">{{ userFacingError(problem, '部分远程分支无法读取，请检查连接与 Git 凭据后刷新。') }}</p>
    <label v-if="rows.length">{{ title }}<select :aria-label="title" :value="rows.some(row => row.id === value) ? value : ''" :disabled="disabled || busy" @change="select(($event.target as HTMLSelectElement).value)"><option value="" disabled>请选择分支</option><option v-for="row in rows" :key="row.id" :value="row.id">{{ repositoryBranchLabel(row.id) }}</option></select></label>
    <p v-else-if="loaded && !busy">没有匹配的分支，请调整搜索条件。</p>
    <button v-if="cursor" type="button" :disabled="disabled || busy" @click="search(true)">更多分支</button>
  </template>
  <p class="workflow-inspector-hint">开始采集时固定所选分支的提交。节点重试沿用该提交；需要新版本时新增采集节点。</p>
</section></template>
