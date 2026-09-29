<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import WorkflowChoice from '@/components/workflow/WorkflowChoice.vue'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError, workflowStateLabel } from '@/utils/displayLabels'
import type { WorkflowRequirementSummary } from '@/types/domain'
import '@/components/workflow/workflow.css'
const rows = ref<WorkflowRequirementSummary[]>([]), project = ref<{ id: string; title: string } | null>(null), cursor = ref<string | null>(null), busy = ref(false), error = ref('')
let generation = 0
async function load(more = false) {
  const ticket = ++generation; busy.value = true; error.value = ''; if (!more) { rows.value = []; cursor.value = null }
  try { const page = await workflowRuns.list(project.value?.id, more ? cursor.value || '' : ''); if (ticket === generation) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null } }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '需求任务暂时无法读取，请重试。') }
  finally { if (ticket === generation) busy.value = false }
}
onMounted(() => { void load() }); onBeforeUnmount(() => { generation++ })
</script>
<template><main id="main-content" class="workflow-page"><PageHeader eyebrow="从需求到交付" title="需求任务"><template #actions><RouterLink class="primary-button" to="/requirements/new">新增需求</RouterLink></template></PageHeader><div class="workflow-library-filters"><WorkflowChoice kind="project" :value="project" clearable @select="value => { project = value; load() }" /><RouterLink to="/workflows">管理流程</RouterLink></div><p v-if="error" role="alert" class="workflow-error">{{ error }}<button @click="load()">重新加载</button></p><p v-if="busy" role="status">正在读取需求…</p><section v-if="!busy && !error && !rows.length" class="workflow-library-empty"><h2>从一个需求开始</h2><p>选择可复用流程，在画布上安排本次工作。</p><RouterLink class="primary-button" to="/requirements/new">新增需求</RouterLink></section><section class="workflow-template-grid" aria-label="需求任务列表"><article v-for="row in rows" :key="row.id" class="workflow-template-card"><StatusBadge :status="row.state === 'COMPLETED' ? 'SUCCEEDED' : row.state" :label="workflowStateLabel(row.state)" /><h2><RouterLink :to="`/requirements/${row.id}`">{{ row.title }}</RouterLink></h2><p>计划版本 {{ row.headRevision }}</p><footer><span>{{ new Date(row.updatedAt).toLocaleString() }}</span><RouterLink :to="`/requirements/${row.id}`">打开画布</RouterLink></footer></article></section><button v-if="cursor" class="workflow-more" :disabled="busy" @click="load(true)">加载更多需求</button></main></template>
