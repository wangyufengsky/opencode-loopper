<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watchEffect } from 'vue'
import type { PptDeck, PptSlide, PptElement } from '@/types/domain'
import { mountReactView } from '@/react/bridge'
import { PptCanvasView, type PptCanvasViewProps } from '@/react/ppt/PptCanvasView'
import { pptDtoSnapshot } from '@/react/ppt/snapshot'
import { useCanvasRuntime } from '@/migration/canvasRuntimeVue'
import PptCanvasLegacy from './PptCanvasLegacy.vue'

const props = defineProps<{
  deck: PptDeck
  slide: PptSlide
  selected: string
  preview?: string
  previewRevision?: number
  revision: number
  disabled?: boolean
  editing?: boolean
}>()
const emit = defineEmits<{
  select: [id: string]
  patch: [id: string, patch: Partial<PptElement>, revision: number]
  remove: [id: string]
}>()
const runtime = useCanvasRuntime()
const host = shallowRef<HTMLElement>()
let view: ReturnType<typeof mountReactView<PptCanvasViewProps>> | undefined
watchEffect(() => {
  const values: PptCanvasViewProps = {
    ...props,
    deck: pptDtoSnapshot(props.deck),
    slide: pptDtoSnapshot(props.slide),
    onSelect: id => emit('select', id),
    onPatch: (id, patch, revision) => emit('patch', id, patch, revision),
    onRemove: id => emit('remove', id),
  }
  if (runtime.value === 'react' && host.value) {
    view ??= mountReactView(host.value, PptCanvasView)
    view.render(values)
  }
}, { flush: 'post' })
onBeforeUnmount(() => view?.unmount())
</script>

<template>
  <PptCanvasLegacy v-if="runtime === 'vue'" v-bind="props"
    @select="emit('select', $event)"
    @patch="(id, patch, revision) => emit('patch', id, patch, revision)"
    @remove="emit('remove', $event)" />
  <div v-else ref="host" style="display: contents" />
</template>
