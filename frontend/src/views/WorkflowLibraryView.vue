<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import type { WorkflowTemplateSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import '@/components/workflow/workflow.css'
const router = useRouter(), items = ref<WorkflowTemplateSummary[]>([]), query = ref(''), kind = ref('ALL'), cursor = ref<string | null>(null), loading = ref(false), error = ref('')
const pending = ref<{ row: WorkflowTemplateSummary; action: 'copy' | 'archive'; key: string } | null>(null), busy = ref(false)
let generation = 0
async function load(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''
  if (!more) { items.value = []; cursor.value = null }
  try { const result = await workflowApi.list(query.value, kind.value, more ? cursor.value || '' : ''); if (ticket !== generation) return; items.value = more ? [...items.value, ...result.items] : result.items; cursor.value = result.nextCursor ?? null }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '流程暂时无法读取，请重试。') }
  finally { if (ticket === generation) loading.value = false }
}
async function act(row: WorkflowTemplateSummary, action: 'copy' | 'archive') {
  if (busy.value) return
  if (!pending.value) {
    if (action === 'archive' && !window.confirm(`删除“${row.title}”？已创建的任务与历史版本会保留。`)) return
    pending.value = { row, action, key: crypto.randomUUID() }
  }
  busy.value = true; error.value = ''
  const operation = pending.value
  try {
    if (operation.action === 'copy') { const result = await workflowApi.copy(operation.row.id, { requestKey: operation.key, sourceRevision: operation.row.headRevision, title: `${operation.row.title} 副本`.slice(0, 120) }); pending.value = null; await router.push(`/workflows/${result.id}`) }
    else { await workflowApi.archive(operation.row.id, { requestKey: operation.key, expectedVersion: operation.row.version }); pending.value = null; await load() }
  } catch (failure) { error.value = userFacingError(failure, '操作未完成，请重试原操作。'); if (failure instanceof ApiError && [400, 404, 409].includes(failure.status)) pending.value = null }
  finally { busy.value = false }
}
onMounted(() => { void load() })
</script>
<template>
  <main id="main-content" class="workflow-page"><PageHeader eyebrow="工作方式" title="流程"><template #actions><RouterLink to="/workflows/new" class="primary-button"><Icon icon="lucide:plus" />新增流程</RouterLink></template></PageHeader>
    <div class="workflow-library-intro"><Icon icon="lucide:workflow" width="32" /><div><h2>把角色和任务，组织成自己的流程。</h2><p>创建可复用的工作安排，在每次需求中继续调整。</p></div></div>
    <form class="workflow-library-filters" @submit.prevent="load()"><div role="group" aria-label="流程来源"><button v-for="tab in [{ key: 'ALL', label: '全部流程' }, { key: 'BUILTIN', label: '程序内置' }, { key: 'CUSTOM', label: '我的流程' }]" :key="tab.key" type="button" :aria-pressed="kind === tab.key" @click="kind = tab.key; load()">{{ tab.label }}</button></div><div class="workflow-inline"><input v-model="query" aria-label="搜索流程" placeholder="搜索流程名称或说明" /><button type="submit">搜索</button></div></form>
    <div v-if="error || pending" role="alert" class="workflow-error">{{ error || '上次操作的结果尚未确认，请重试原操作。' }}<button v-if="pending" :disabled="busy" @click="act(pending.row, pending.action)">重试原操作</button><button v-else @click="load()">重新加载</button></div>
    <p v-if="loading && !items.length" role="status">正在加载流程…</p>
    <section v-else-if="!items.length && !error" class="workflow-library-empty"><Icon icon="lucide:workflow" width="48" /><h2>{{ query ? '没有找到匹配的流程' : '这里还没有流程' }}</h2><p>{{ kind === 'BUILTIN' ? '当前程序没有已发布的内置流程。' : '从自由任务或人工检查开始，保存自己的工作安排。' }}</p><RouterLink v-if="kind !== 'BUILTIN'" to="/workflows/new" class="primary-button">创建第一个流程</RouterLink></section>
    <section class="workflow-template-grid" aria-label="流程列表"><article v-for="row in items" :key="row.id" class="workflow-template-card"><div class="workflow-template-mark"><Icon icon="lucide:workflow" /><span>{{ row.builtin ? '程序内置' : '自定义' }}</span></div><h2><RouterLink :to="`/workflows/${row.id}`">{{ row.title }}</RouterLink></h2><p>{{ row.description || '暂无说明' }}</p><footer><span>版本 {{ row.headRevision }}</span><RouterLink :to="`/workflows/${row.id}`">{{ row.builtin ? '查看流程' : '编辑流程' }}</RouterLink><RouterLink :to="{ path: '/requirements/new', query: { template: row.id } }">使用流程</RouterLink><button :disabled="busy || !!pending" @click="act(row, 'copy')">复制</button><button v-if="!row.builtin" :disabled="busy || !!pending" @click="act(row, 'archive')">删除</button></footer></article></section>
    <button v-if="cursor" class="workflow-more" :disabled="loading" @click="load(true)">{{ loading ? '正在加载…' : '加载更多流程' }}</button>
  </main>
</template>
