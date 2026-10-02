<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import StatusBadge from '@/components/StatusBadge.vue'
import WorkflowChoice from '@/components/workflow/WorkflowChoice.vue'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError, workflowStateLabel } from '@/utils/displayLabels'
import type { WorkflowRequirementSummary } from '@/types/domain'
import '@/components/workflow/workflow.css'
import '@/components/workflow/workflow-entry.css'
const rows = ref<WorkflowRequirementSummary[]>([]), project = ref<{ id: string; title: string } | null>(null), cursor = ref<string | null>(null), busy = ref(false), error = ref('')
let generation = 0
const dateFormat = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
function updatedAt(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '时间待更新' : dateFormat.format(date) }
async function load(more = false) {
  const ticket = ++generation; busy.value = true; error.value = ''; if (!more) { rows.value = []; cursor.value = null }
  try { const page = await workflowRuns.list(project.value?.id, more ? cursor.value || '' : ''); if (ticket === generation) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null } }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '需求任务暂时无法读取，请重试。') }
  finally { if (ticket === generation) busy.value = false }
}
onMounted(() => { void load() }); onBeforeUnmount(() => { generation++ })
</script>
<template>
  <main id="main-content" class="workflow-page workflow-entry">
    <header class="workflow-entry-header">
      <div><h1>需求任务</h1><p>从目标到交付，每一次工作的进展。</p></div>
      <RouterLink class="primary-button" :to="{ path: '/requirements/new', query: project ? { projectId: project.id } : {} }"><Icon icon="lucide:plus" aria-hidden="true" />新增需求</RouterLink>
    </header>
    <div class="workflow-entry-toolbar">
      <div class="workflow-entry-project-filter"><span>工作项目</span><WorkflowChoice kind="project" :value="project" clearable @select="value => { project = value; load() }" /></div>
      <RouterLink class="workflow-entry-link" to="/workflows"><Icon icon="lucide:workflow" aria-hidden="true" />管理流程<Icon icon="lucide:arrow-up-right" aria-hidden="true" /></RouterLink>
    </div>
    <div v-if="error" role="alert" class="workflow-error"><Icon icon="lucide:circle-alert" aria-hidden="true" /><span>{{ error }}</span><button :disabled="busy" @click="load()">重新加载</button></div>
    <p v-if="busy" role="status" class="workflow-entry-status">正在读取需求…</p>
    <section v-if="!busy && !error && !rows.length" class="workflow-entry-empty">
      <span class="workflow-entry-empty-icon"><Icon icon="lucide:layout-dashboard" aria-hidden="true" /></span>
      <h2>{{ project ? '这个项目还没有需求' : '从一个需求开始' }}</h2><p>选择流程，在画布上安排本次工作。</p>
      <RouterLink class="primary-button" :to="{ path: '/requirements/new', query: project ? { projectId: project.id } : {} }">新增需求</RouterLink>
    </section>
    <section v-if="rows.length" class="workflow-entry-requirements" aria-label="需求任务列表" :aria-busy="busy">
      <div class="workflow-entry-list-heading" aria-hidden="true"><span>需求</span><span>状态</span><span>最近更新</span><span></span></div>
      <article v-for="row in rows" :key="row.id" class="workflow-entry-requirement">
        <div class="workflow-entry-requirement-title"><span class="workflow-entry-icon"><Icon icon="lucide:git-pull-request" aria-hidden="true" /></span><div><h2><RouterLink :to="`/requirements/${row.id}`">{{ row.title }}</RouterLink></h2><p class="workflow-entry-version">计划版本 {{ row.headRevision }}</p></div></div>
        <StatusBadge :status="row.state === 'COMPLETED' ? 'SUCCEEDED' : row.state" :label="workflowStateLabel(row.state)" />
        <time :datetime="row.updatedAt" :title="new Date(row.updatedAt).toLocaleString('zh-CN')">{{ updatedAt(row.updatedAt) }}</time>
        <RouterLink class="workflow-entry-open" :to="`/requirements/${row.id}`">打开画布<Icon icon="lucide:arrow-up-right" aria-hidden="true" /></RouterLink>
      </article>
    </section>
    <div v-if="cursor" class="workflow-entry-pagination"><button :disabled="busy" @click="load(true)">{{ busy ? '正在加载…' : '加载更多需求' }}<Icon icon="lucide:chevron-down" aria-hidden="true" /></button></div>
  </main>
</template>
