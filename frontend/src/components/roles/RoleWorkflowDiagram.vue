<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { RoleSlotBinding } from '@/types/domain'
import { mountReactView } from '@/react/bridge'
import { RoleDiagram, type RoleDiagramProps } from '@/react/diagrams/RoleDiagram'
import { useCanvasRuntime } from '@/migration/canvasRuntimeVue'
import RoleWorkflowDiagramLegacy from './RoleWorkflowDiagramLegacy.vue'
const props = defineProps<{ bindings: RoleSlotBinding[]; latestRevisionId: string }>()
const emit = defineEmits<{ revision: [id: string] }>()
const runtime = useCanvasRuntime(), host = ref<HTMLElement>()
let view: ReturnType<typeof mountReactView<RoleDiagramProps>> | undefined
function render() { view?.render({ bindings: props.bindings.map(binding => ({ ...binding })), latestRevisionId: props.latestRevisionId, onRevision: id => emit('revision', id) }) }
onMounted(() => { if (host.value) { view = mountReactView(host.value, RoleDiagram); render() } })
watch(() => [props.bindings, props.latestRevisionId], render, { deep: true })
onBeforeUnmount(() => view?.unmount())
</script>
<template>
  <RoleWorkflowDiagramLegacy v-if="runtime === 'vue'" :bindings="bindings" :latest-revision-id="latestRevisionId" @revision="emit('revision', $event)" />
  <div v-else ref="host" data-canvas-runtime="react" data-canvas-kind="roles" />
</template>
