<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import type { WorkflowTemplateSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import '@/components/workflow/workflow.css'
import '@/components/workflow/workflow-entry.css'
const router = useRouter(), items = ref<WorkflowTemplateSummary[]>([]), query = ref(''), kind = ref('ALL'), cursor = ref<string | null>(null), loading = ref(false), error = ref('')
const pending = ref<{ row: WorkflowTemplateSummary; action: 'copy' | 'archive'; key: string } | null>(null), busy = ref(false)
const openActions = ref<string | null>(null), actionId = useId()
let generation = 0
async function load(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''; openActions.value = null
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
function closeActions(event: KeyboardEvent) {
  openActions.value = null
  ;(event.currentTarget as HTMLElement).querySelector<HTMLButtonElement>('[data-action-trigger]')?.focus()
}
function leaveActions(event: FocusEvent) {
  if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) openActions.value = null
}
const dismissActions = (event: PointerEvent) => {
  if (!(event.target instanceof Element) || !event.target.closest('.workflow-entry-actions')) openActions.value = null
}
onMounted(() => { void load(); document.addEventListener('pointerdown', dismissActions) })
onBeforeUnmount(() => { generation++; document.removeEventListener('pointerdown', dismissActions) })
</script>
<template>
  <main id="main-content" class="workflow-page workflow-entry">
    <header class="workflow-entry-header">
      <div><h1>流程</h1><p>可复用的工作方式，从这里开始。</p></div>
      <RouterLink to="/workflows/new" class="primary-button"><Icon icon="lucide:plus" aria-hidden="true" />新增流程</RouterLink>
    </header>
    <form class="workflow-entry-toolbar" @submit.prevent="load()">
      <div class="workflow-entry-tabs" role="group" aria-label="流程来源">
        <button v-for="tab in [{ key: 'ALL', label: '全部流程' }, { key: 'BUILTIN', label: '程序内置' }, { key: 'CUSTOM', label: '我的流程' }]" :key="tab.key" type="button" :aria-pressed="kind === tab.key" @click="kind = tab.key; load()">{{ tab.label }}</button>
      </div>
      <div class="workflow-entry-search"><Icon icon="lucide:search" aria-hidden="true" /><input v-model="query" aria-label="搜索流程" placeholder="搜索名称或说明" /><button type="submit">搜索</button></div>
    </form>
    <div v-if="error || (pending && !busy)" role="alert" class="workflow-error">
      <Icon icon="lucide:circle-alert" aria-hidden="true" /><span>{{ error || '上次操作的结果尚未确认，请重试原操作。' }}</span>
      <button v-if="pending" :disabled="busy" @click="act(pending.row, pending.action)">重试原操作</button><button v-else :disabled="loading" @click="load()">重新加载</button>
    </div>
    <p v-if="busy" role="status" class="workflow-entry-status">{{ pending?.action === 'archive' ? '正在删除流程…' : '正在复制流程…' }}</p>
    <p v-if="loading && !items.length" role="status" class="workflow-entry-status">正在加载流程…</p>
    <section v-else-if="!items.length && !error" class="workflow-entry-empty">
      <span class="workflow-entry-empty-icon"><Icon :icon="query ? 'lucide:search' : 'lucide:workflow'" aria-hidden="true" /></span>
      <h2>{{ query ? '没有找到匹配的流程' : '这里还没有流程' }}</h2>
      <p>{{ query ? '试试其他关键词，或切换流程来源。' : kind === 'BUILTIN' ? '当前程序没有已发布的内置流程。' : '从自由任务或人工检查开始，保存自己的工作安排。' }}</p>
      <RouterLink v-if="!query && kind !== 'BUILTIN'" to="/workflows/new" class="primary-button">创建第一个流程</RouterLink>
    </section>
    <section class="workflow-template-grid" aria-label="流程列表" :aria-busy="loading">
      <article v-for="(row, index) in items" :key="row.id" class="workflow-template-card">
        <header class="workflow-entry-card-top">
          <div class="workflow-template-mark"><span class="workflow-entry-icon"><Icon icon="lucide:workflow" aria-hidden="true" /></span><span>{{ row.builtin ? '程序内置' : '自定义' }}</span></div>
          <div class="workflow-entry-actions" @keydown.esc.stop="closeActions" @focusout="leaveActions">
            <button type="button" class="workflow-entry-more" data-action-trigger :aria-label="`更多操作：${row.title}`" :aria-expanded="openActions === row.id" :aria-controls="`${actionId}-${index}`" @click="openActions = openActions === row.id ? null : row.id"><Icon icon="lucide:ellipsis" aria-hidden="true" /></button>
            <div v-if="openActions === row.id" :id="`${actionId}-${index}`" class="workflow-entry-action-panel" role="group" aria-label="流程操作">
              <RouterLink :to="`/workflows/${row.id}`"><Icon :icon="row.builtin ? 'lucide:scan-eye' : 'lucide:pencil'" aria-hidden="true" />{{ row.builtin ? '查看流程' : '编辑流程' }}</RouterLink>
              <button type="button" :disabled="busy || !!pending" @click="act(row, 'copy')"><Icon icon="lucide:copy" aria-hidden="true" />复制</button>
              <button v-if="!row.builtin" type="button" class="danger" :disabled="busy || !!pending" @click="act(row, 'archive')"><Icon icon="lucide:trash-2" aria-hidden="true" />删除</button>
            </div>
          </div>
        </header>
        <h2><RouterLink :to="`/workflows/${row.id}`">{{ row.title }}</RouterLink></h2>
        <p :title="row.description">{{ row.description || '暂无说明' }}</p>
        <footer><span class="workflow-entry-version">版本 {{ row.headRevision }}</span><RouterLink class="workflow-entry-use" :to="{ path: '/requirements/new', query: { template: row.id } }">使用流程<Icon icon="lucide:arrow-up-right" aria-hidden="true" /></RouterLink></footer>
      </article>
    </section>
    <div v-if="cursor" class="workflow-entry-pagination"><button :disabled="loading" @click="load(true)">{{ loading ? '正在加载…' : '加载更多流程' }}<Icon icon="lucide:chevron-down" aria-hidden="true" /></button></div>
  </main>
</template>
