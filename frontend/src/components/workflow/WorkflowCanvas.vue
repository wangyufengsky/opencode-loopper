<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { WorkflowGraph, WorkflowLayout } from '@/types/domain'
import { mountReactView } from '@/react/bridge'
import { useCanvasRuntime } from '@/migration/canvasRuntimeVue'
import { WorkflowCanvasView } from '@/react/workflow/WorkflowCanvasReact'
import type { WorkflowCanvasHandle, WorkflowCanvasProps } from '@/react/workflow/types'
import WorkflowCanvasLegacy from './WorkflowCanvasLegacy.vue'
import { clone } from './graph'

const props = defineProps<{ graph: WorkflowGraph; layout: WorkflowLayout; selected?: string; selectedEdge?: string; readonly?: boolean; movable?: boolean; connecting?: string; roleNames?: Record<string, string>; states?: Record<string, string> }>()
const emit = defineEmits<{ select: [id: string]; edge: [id: string]; connect: [id: string]; 'connect-pair': [from: string, to: string]; layout: [layout: WorkflowLayout]; remove: [id: string]; cancel: [] }>()
const runtime = useCanvasRuntime(), host = ref<HTMLElement>(), legacy = ref<InstanceType<typeof WorkflowCanvasLegacy>>()
let reactView: ReturnType<typeof mountReactView<WorkflowCanvasProps>> | undefined, controller: WorkflowCanvasHandle | undefined
const callbacks = {
  onSelect: (id: string) => emit('select', id), onEdge: (id: string) => emit('edge', id), onConnect: (id: string) => emit('connect', id),
  onConnectPair: (from: string, to: string) => emit('connect-pair', from, to), onLayout: (layout: WorkflowLayout) => emit('layout', layout),
  onRemove: (id: string) => emit('remove', id), onCancel: () => emit('cancel'), onReady: (handle: WorkflowCanvasHandle | undefined) => { controller = handle },
}
function render() { reactView?.render({ ...clone(props), ...callbacks }) }
onMounted(() => { if (runtime.value === 'react' && host.value) { reactView = mountReactView(host.value, WorkflowCanvasView); render() } })
watch(props, render, { deep: true, flush: 'post' })
onBeforeUnmount(() => { reactView?.unmount(); reactView = undefined; controller = undefined })
function fit() { if (runtime.value === 'vue') legacy.value?.fit(); else controller?.fit() }
function focus(id?: string) { if (runtime.value === 'vue') legacy.value?.focus(id); else controller?.focus(id) }
function reveal(id: string) { if (runtime.value === 'vue') legacy.value?.reveal(id); else controller?.reveal(id) }
defineExpose({ fit, focus, reveal })
</script>
<template>
  <WorkflowCanvasLegacy v-if="runtime === 'vue'" ref="legacy" v-bind="props" @select="emit('select', $event)" @edge="emit('edge', $event)" @connect="emit('connect', $event)" @layout="emit('layout', $event)" @remove="emit('remove', $event)" @cancel="emit('cancel')" />
  <div v-else ref="host" class="workflow-react-host" />
</template>
