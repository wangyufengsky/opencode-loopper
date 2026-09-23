<script setup lang="ts">
import { ElAlert } from 'element-plus'
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { SourceTemplateCoverage } from '@/types/domain'
import { sourceCoverageLabel, userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ runId: string; revision: string; ready: boolean }>()
const rows = ref<SourceTemplateCoverage[]>([]), next = ref<string | null>()
const expanded = ref<Record<string, string[]>>({})
const loading = ref(false), error = ref('')
let generation = 0
async function load(append = false) {
  if (!props.ready || loading.value) return
  const token = generation; loading.value = true; error.value = ''
  try {
    const page = await api.sourceCoverage(props.runId, append ? next.value ?? '' : '')
    if (token !== generation) return
    rows.value = append ? [...rows.value, ...page.items] : page.items; next.value = page.nextCursor
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '覆盖清单读取失败，请重试') }
  finally { if (token === generation) loading.value = false }
}
watch(() => [props.runId, props.revision, props.ready], () => {
  ++generation; rows.value = []; next.value = null; expanded.value = {}; loading.value = false; void load()
}, { immediate: true })
async function details(row: SourceTemplateCoverage) {
  if (expanded.value[row.path]) { delete expanded.value[row.path]; return }
  const token = generation
  try {
    const result = await api.sourceCoverageItem(props.runId, row.path)
    const mapping: unknown = JSON.parse(result.resultJson || '{}')
    let text: string[] = []
    if (Array.isArray(mapping)) text = mapping.map(item => `${String(item.title || '已验证场景')} · ${Array.isArray(item.verifications) ? item.verifications.length : 0} 项正式验证`)
    else if (mapping && typeof mapping === 'object' && 'documents' in mapping && Array.isArray(mapping.documents))
      text = mapping.documents.map(item => `文档章节：${String(item)}`)
    if (token === generation) expanded.value[row.path] = text.length ? text : [row.exclusion || '尚未形成完成依据']
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '处理依据读取失败，请重试') }
}
onBeforeUnmount(() => { ++generation })
</script>
<template>
  <section class="card card-pad source-coverage" aria-label="源码覆盖清单">
    <header><h2>源码覆盖清单</h2><el-button :loading="loading" :disabled="!ready" @click="load()">刷新清单</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!ready" class="muted">源码冻结完成后显示逐文件处理结果。</p>
    <div v-else class="coverage-rows">
      <article v-for="row in rows" :key="row.path">
        <header><strong>{{ row.path }}</strong><span>{{ sourceCoverageLabel(row.status) }}</span><el-button text @click="details(row)">{{ expanded[row.path] ? '收起依据' : '查看依据' }}</el-button></header>
        <p v-if="row.exclusion" class="muted">{{ row.exclusion }}</p>
        <ul v-if="expanded[row.path]"><li v-for="(item, index) in expanded[row.path]" :key="index">{{ item }}</li></ul>
      </article>
    </div>
    <el-button v-if="next" :loading="loading" @click="load(true)">加载更多文件</el-button>
  </section>
</template>
<style scoped>
.source-coverage { display: grid; gap: 16px; min-width: 0; }header { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }h2 { margin: 0; font-size: 18px; }.coverage-rows { max-height: 550px; overflow: auto; }.coverage-rows article { padding: 12px 0; border-bottom: 1px solid var(--color-border-default); overflow-wrap: anywhere; }.coverage-rows strong { flex: 1; min-width: 180px; }p { margin: 8px 0; }
</style>
