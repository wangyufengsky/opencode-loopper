<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
const emit = defineEmits<{ checked: [value: WorkflowWritebackPreview | null] }>()
const props = defineProps<{ source: WorkflowPublicationPreview; disabled?: boolean }>()
const result = ref<WorkflowWritebackPreview | null>(null), reading = ref(false), error = ref('')
let generation = 0, controller: AbortController | undefined
function reset() { generation++; controller?.abort(); reading.value = false; error.value = ''; result.value = null; emit('checked', null) }
async function inspect() {
  if (props.disabled || reading.value || props.source.workspaceKind !== 'DIRECT' || props.source.requirementState !== 'COMPLETED') return
  reset(); const current = generation, source = props.source; controller = new AbortController(); reading.value = true
  try {
    const value = await workflowWriteback.preview(source.requirementId, { revision: source.planRevision, node: source.source.nodeKey, attempt: source.source.attemptId, output: source.source.outputName, sourceSha256: source.sha256 }, controller.signal)
    if (current !== generation) return
    if (value.requirementId !== source.requirementId || value.sourceSha256 !== source.sha256 || value.revision !== source.planRevision || value.requirementVersion !== source.requirementVersion) throw new Error('目录检查与所选成果版本不一致，请刷新成果后重新检查。')
    result.value = value; emit('checked', value)
  } catch (failure) { if (current === generation) error.value = userFacingError(failure, '原目录暂时无法检查，请确认路径和文件状态后重试。') }
  finally { if (current === generation) reading.value = false }
}
watch(() => [props.source.requirementId, props.source.sha256], reset)
onBeforeUnmount(reset)
</script>
<template>
  <section v-if="source.workspaceKind === 'DIRECT' && source.requirementState === 'COMPLETED'" class="writeback-preview" aria-label="普通目录回填检查">
    <h3>回填前检查</h3><p>将所选成果与原目录当前文件比较，保留成果未改动路径上的后续修改。</p>
    <button :disabled="disabled || reading" @click="inspect">{{ reading ? '正在检查原目录…' : result ? '重新检查原目录' : '检查原目录' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="result"><p>原目录：{{ result.directory }}</p>
      <template v-if="result.conflictCount"><p role="alert">有 {{ result.conflictCount }} 个路径与所选成果冲突，请先核对这些文件，再重新检查。</p><ul><li v-for="path in result.conflicts" :key="path"><code>{{ path }}</code></li></ul><p v-if="result.conflictCount > result.conflicts.length">仅展示前 {{ result.conflicts.length }} 个冲突路径。</p></template>
      <p v-else>预计新增 {{ result.added }} · 修改 {{ result.modified }} · 删除 {{ result.deleted }}</p>
      <p>保留 {{ result.preservedChanges }} 处用户后续修改。本次检查没有写入文件。</p>
    </template>
  </section>
</template>
<style scoped>
.writeback-preview { border-top: 1px solid var(--color-border-default); margin-top: 1rem; padding-top: .75rem; overflow-wrap: anywhere; }
.writeback-preview h3 { font-size: 1rem; } .writeback-preview ul { max-height: 15rem; overflow: auto; padding-left: 1.5rem; }
</style>
