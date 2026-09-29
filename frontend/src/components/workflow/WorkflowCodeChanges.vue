<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCodeChange } from '@/types/domain'
import { userFacingError, workflowCodeChangeLabel } from '@/utils/displayLabels'
const props = defineProps<{ requirement: string; node: string; attempt: string; direction: 'inputs' | 'outputs'; name: string }>()
const rows = ref<WorkflowCodeChange[]>([]), cursor = ref<string | null>(null), loaded = ref(false), busy = ref(false), error = ref('')
let alive = true, failedMore = false
async function load(more = false) {
  if (busy.value) return
  busy.value = true; error.value = ''; failedMore = more
  try {
    const page = await workflowRuns.changes(props.requirement, props.node, props.attempt, props.direction, props.name, more ? cursor.value || '' : '')
    if (alive) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null; loaded.value = true }
  } catch (failure) { if (alive) error.value = userFacingError(failure, '改动文件暂时无法读取，请重试。') }
  finally { if (alive) busy.value = false }
}
onBeforeUnmount(() => { alive = false })
</script>
<template>
  <section class="workflow-code-changes" aria-label="代码改动文件">
    <button v-if="!loaded && !error" type="button" :disabled="busy" @click="load()">{{ busy ? '读取改动…' : '查看改动文件' }}</button>
    <template v-if="loaded">
      <h5>改动文件</h5>
      <p class="workflow-code-changes-note">相对原始基线的累计改动，包含继承的上游代码。</p>
      <p v-if="!rows.length">该交付物与原始基线没有文件改动。</p>
      <ul v-else>
        <li v-for="change in rows" :key="change.path">
          <span class="workflow-code-change-kind">{{ workflowCodeChangeLabel(change.kind) }}</span>
          <span v-if="change.kind === 'DELETE'">{{ change.path }}</span>
          <a v-else :href="workflowRuns.fileUrl(requirement, node, attempt, direction, name, change.path)" download>{{ change.path }}</a>
        </li>
      </ul>
    </template>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="load(failedMore)">重试读取改动</button></p>
    <button v-else-if="cursor" type="button" :disabled="busy" @click="load(true)">{{ busy ? '读取改动…' : '更多改动文件' }}</button>
  </section>
</template>
<style scoped>
.workflow-code-changes { margin-block: .75rem; }
h5 { font-size: inherit; margin: 0; }
.workflow-code-changes-note { color: var(--color-text-secondary); font-size: .85rem; }
ul { list-style: none; padding: 0; }
li { display: flex; align-items: baseline; gap: .75rem; margin-block: .45rem; overflow-wrap: anywhere; }
li > a, li > span:last-child { font-size: .85rem; }
.workflow-code-change-kind { flex: 0 0 auto; font-size: .85rem; color: var(--color-text-secondary); }
</style>
