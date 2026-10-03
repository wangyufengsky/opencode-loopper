<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Stage } from '@/types/domain'
import { mountReactView } from '@/react/bridge'
import { StageDiagram } from '@/react/diagrams/StageDiagram'
import { useCanvasRuntime } from '@/migration/canvasRuntimeVue'
import StageRailLegacy from './StageRailLegacy.vue'
const props = defineProps<{ stages: Stage[] }>()
const runtime = useCanvasRuntime(), host = ref<HTMLElement>()
let view: ReturnType<typeof mountReactView<{ stages: Stage[] }>> | undefined
function render() { view?.render({ stages: props.stages.map(stage => ({ ...stage, attempts: [...stage.attempts] })) }) }
onMounted(() => { if (host.value) { view = mountReactView(host.value, StageDiagram); render() } })
watch(() => props.stages, render, { deep: true })
onBeforeUnmount(() => view?.unmount())
</script>
<template>
  <StageRailLegacy v-if="runtime === 'vue'" :stages="stages" />
  <div v-else ref="host" class="stage-diagram-host" data-canvas-runtime="react" data-canvas-kind="stages" />
</template>
