<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { usePptStore } from '@/stores/pptStore'
import PptSources from './PptSources.vue'
import PptHistory from './PptHistory.vue'
import PptPlanEditor from './PptPlanEditor.vue'

type Detail = 'sources' | 'history' | 'plan' | null
const props = defineProps<{
  modelValue: Detail
  ready?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: Detail]
  view: [revision: number]
  restore: [revision: number]
  insert: [id: string]
}>()
const store = usePptStore()
const dialog = ref<HTMLDialogElement>()
const dirty = ref(false)
const titles = {
  sources: '资料与素材',
  history: '版本与导出',
  plan: '制作方案',
}
watch(
  () => props.modelValue,
  async (value) => {
    await nextTick()
    if (value && !dialog.value?.open) dialog.value?.showModal()
    if (!value && dialog.value?.open) dialog.value.close()
  },
)

async function editPlan() {
  if (window.confirm('修改制作方案？现有页面和导出会保留，保存后可让助手重新制作。'))
    await store.action('reopen')
}
async function applyPlan() {
  if (await store.generate('请按照当前已保存的制作方案和资料，重新完成演示文稿制作。'))
    emit('update:modelValue', null)
}
</script>

<template>
  <dialog
    ref="dialog"
    class="ppt-detail-dialog"
    :aria-label="modelValue ? titles[modelValue] : '作品详情'"
    @close="emit('update:modelValue', null)"
    @click.self="dialog?.close()"
  >
    <header class="ppt-detail-heading">
      <h2>{{ modelValue ? titles[modelValue] : '' }}</h2>
      <button aria-label="关闭详情" @click="dialog?.close()">
        <Icon icon="lucide:x" />
      </button>
    </header>
    <div class="ppt-detail-body">
      <PptSources
        v-if="modelValue === 'sources'"
        :allow-insert="ready"
        @insert="emit('insert', $event)"
      />
      <PptHistory
        v-else-if="modelValue === 'history'"
        @view="emit('view', $event)"
        @restore="emit('restore', $event)"
      />
      <template v-else-if="modelValue === 'plan' && store.document">
        <div class="ppt-plan-actions">
          <p>助手使用这份方案组织内容和页面。</p>
          <button
            v-if="!['BRIEFING', 'DIRECTION', 'DESIGN'].includes(store.document.phase)"
            :disabled="store.active || store.busy || store.document.archived"
            @click="editPlan"
          >
            修改方案
          </button>
          <button
            v-else
            class="ppt-primary"
            :disabled="store.active || store.busy || dirty || store.document.archived"
            @click="applyPlan"
          >
            让助手应用方案
            <Icon icon="lucide:arrow-right" />
          </button>
        </div>
        <PptPlanEditor
          :plan="store.plan"
          :document-id="store.document.id"
          :revision="store.document.revision"
          :capabilities="store.capabilities"
          :sources="store.sources"
          :disabled="
            !store.editable ||
            store.active ||
            !['BRIEFING', 'DIRECTION', 'DESIGN'].includes(store.document.phase)
          "
          :direction-frozen="store.document.phase === 'DESIGN'"
          @save="store.savePlan"
          @dirty="dirty = $event"
        />
      </template>
    </div>
  </dialog>
</template>
