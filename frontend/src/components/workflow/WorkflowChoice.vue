<script setup lang="ts">
import { onBeforeUnmount, ref, useId, watch } from 'vue'
import { workflowApi } from '@/api/workflow'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ kind: 'project' | 'template'; value?: { id: string; title: string } | null; disabled?: boolean; clearable?: boolean }>()
const emit = defineEmits<{ select: [value: { id: string; title: string; revision?: number } | null] }>()
const uid = useId(), open = ref(false), query = ref(''), rows = ref<Array<{ id: string; title: string; revision?: number }>>([]), cursor = ref<string | null>(null), loading = ref(false), error = ref('')
let generation = 0
async function load(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''; if (!more) { rows.value = []; cursor.value = null }
  try {
    const page = props.kind === 'project' ? await workflowRuns.projects(query.value, more ? cursor.value || '' : '') : await workflowApi.list(query.value, 'ALL', more ? cursor.value || '' : '')
    if (ticket !== generation) return
    const choices = page.items.map(item => ({ id: item.id, title: 'name' in item ? item.name : item.title, ...('headRevision' in item ? { revision: item.headRevision } : {}) }))
    rows.value = more ? [...rows.value, ...choices] : choices; cursor.value = page.nextCursor || null
  } catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '列表暂时无法读取，请重试。') }
  finally { if (ticket === generation) loading.value = false }
}
function select(row: { id: string; title: string; revision?: number } | null) { if (!props.disabled) { emit('select', row); open.value = false } }
watch(open, value => { if (value) void load() })
watch(() => props.disabled, value => { if (value) open.value = false })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <div class="workflow-choice" @keydown.esc.stop="open = false"><button type="button" :disabled="disabled" :aria-expanded="open" :aria-controls="uid" :aria-label="kind === 'project' ? '选择项目' : '选择流程'" @click="open = !open">{{ value?.title || (kind === 'project' ? '选择项目' : '选择流程') }} <span aria-hidden="true">⌄</span></button>
    <section v-if="open" :id="uid" class="workflow-choice-popover"><div class="workflow-inline"><input v-model="query" :aria-label="kind === 'project' ? '搜索项目' : '搜索可用流程'" placeholder="输入名称搜索" @keydown.enter.prevent="load()" /><button type="button" :disabled="loading" @click="load()">搜索</button></div>
      <p v-if="error" role="alert">{{ error }}<button type="button" @click="load()">重试</button></p><p v-if="loading" role="status">正在读取…</p>
      <button v-if="clearable" type="button" @click="select(null)">全部项目</button><div class="workflow-choice-options"><button v-for="row in rows" :key="row.id" type="button" :aria-pressed="row.id === value?.id" @click="select(row)">{{ row.title }}<small v-if="row.revision">版本 {{ row.revision }}</small></button></div>
      <p v-if="!loading && !error && !rows.length">暂无可选项。<RouterLink v-if="kind === 'template'" to="/workflows/new">创建流程</RouterLink></p><button v-if="cursor" type="button" :disabled="loading" @click="load(true)">加载更多</button><button type="button" @click="open = false">收起</button>
    </section>
  </div>
</template>
