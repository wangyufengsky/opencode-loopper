<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watchEffect } from 'vue'
import type { PptDeck, PptJob } from '@/types/domain'
import { pptApi } from '@/api/ppt'
import { mountReactView } from '@/react/bridge'
import { PptSlideNavigatorView, type PptSlideNavigatorViewProps } from '@/react/ppt/PptSlideNavigatorView'
import { slidePreviews } from '@/react/ppt/previews'
import { pptDtoSnapshot } from '@/react/ppt/snapshot'
import { useCanvasRuntime } from '@/migration/canvasRuntimeVue'
import PptSlideNavigatorLegacy from './PptSlideNavigatorLegacy.vue'

const props = defineProps<{
  documentId: string
  deck: PptDeck
  jobs: PptJob[]
  selected: string
  revision: number
  manual?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{
  select: [id: string]
  add: []
}>()
const runtime = useCanvasRuntime()
const host = shallowRef<HTMLElement>()
const previews = computed(() => slidePreviews(props.jobs, props.revision,
  id => pptApi.artifactUrl(props.documentId, id)))
let view: ReturnType<typeof mountReactView<PptSlideNavigatorViewProps>> | undefined
watchEffect(() => {
  const values: PptSlideNavigatorViewProps = {
    deck: pptDtoSnapshot(props.deck),
    selected: props.selected,
    manual: props.manual,
    disabled: props.disabled,
    previews: previews.value,
    onSelect: id => emit('select', id),
    onAdd: () => emit('add'),
  }
  if (runtime.value === 'react' && host.value) {
    view ??= mountReactView(host.value, PptSlideNavigatorView)
    view.render(values)
  }
}, { flush: 'post' })
onBeforeUnmount(() => view?.unmount())
</script>

<template>
  <PptSlideNavigatorLegacy v-if="runtime === 'vue'" v-bind="props"
    @select="emit('select', $event)" @add="emit('add')" />
  <div v-else ref="host" style="display: contents" />
</template>
