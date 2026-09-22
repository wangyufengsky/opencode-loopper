<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PptSlide } from '@/types/domain'
import { usePptAutosave } from './usePptAutosave'
const props = defineProps<{
  slide: PptSlide
  revision: number
  documentId?: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  save: [id: string, patch: Partial<PptSlide>, revision: number]
  dirty: [value: boolean]
}>()
const draft = ref({
    title: '',
    section: '',
    notes: '',
  }),
  baseline = ref(''),
  baseRevision = ref(-1)
const dirty = computed(() => JSON.stringify(draft.value) !== baseline.value)

function value() {
  return {
    title: props.slide.title,
    section: props.slide.section,
    notes: props.slide.notes,
  }
}

function reload() {
  draft.value = value()
  baseline.value = JSON.stringify(draft.value)
  baseRevision.value = props.revision
}
const storageKey = () =>
  props.documentId ? `loopper.ppt.slideDraft.${props.documentId}.${props.slide.id}` : ''

function initialize() {
  let saved = ''
  try {
    saved = sessionStorage.getItem(storageKey()) || ''
  } catch {
    /* Optional recovery. */
  }
  reload()
  if (saved)
    try {
      const previous = JSON.parse(saved) as {
        draft: typeof draft.value
        baseline: string
        revision: number
      }
      draft.value = previous.draft
      baseline.value = previous.baseline
      baseRevision.value = previous.revision
    } catch {
      /* Ignore a corrupt draft. */
    }
}
watch(() => props.slide.id, initialize, {
  immediate: true,
})
watch(
  () => props.revision,
  () => {
    if (!dirty.value || JSON.stringify(value()) === JSON.stringify(draft.value)) reload()
  },
)
watch(dirty, (value) => emit('dirty', value), {
  immediate: true,
})
watch(
  draft,
  () => {
    if (!storageKey()) return
    try {
      if (dirty.value)
        sessionStorage.setItem(
          storageKey(),
          JSON.stringify({
            draft: draft.value,
            baseline: baseline.value,
            revision: baseRevision.value,
          }),
        )
      else sessionStorage.removeItem(storageKey())
    } catch {
      /* Preserve the visible input. */
    }
  },
  {
    deep: true,
  },
)
const autosave = usePptAutosave(
  () => JSON.stringify(draft.value),
  () => dirty.value,
  () => !props.disabled && !props.slide.locked && baseRevision.value === props.revision,
  () => baseRevision.value,
  () =>
    emit(
      'save',
      props.slide.id,
      {
        ...draft.value,
      },
      baseRevision.value,
    ),
)
</script>
<template>
  <section class="ppt-properties" aria-label="页面属性">
    <h2>页面属性</h2>
    <p v-if="dirty && baseRevision !== revision" class="ppt-notice" role="alert">
      页面已有更新，当前输入仍保留。
      <button @click="reload">读取最新页面</button>
    </p>
    <form @submit.prevent="autosave.save">
      <fieldset :disabled="disabled || slide.locked">
        <label>
          页面标题
          <input v-model="draft.title" />
        </label>
        <label>
          所属章节
          <input v-model="draft.section" />
        </label>
        <label>
          演讲备注
          <textarea v-model="draft.notes" rows="12" />
        </label>
        <button class="ppt-primary" :disabled="!dirty || baseRevision !== revision">
          立即保存页面信息
        </button>
      </fieldset>
    </form>
    <p class="ppt-muted">在画布中选择对象，可以调整文字、样式和位置。</p>
  </section>
</template>
