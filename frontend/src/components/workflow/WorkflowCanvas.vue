<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import type { WorkflowGraph, WorkflowLayout, WorkflowPoint } from '@/types/domain'
import { autoLayout, clone, NODE_HEIGHT, NODE_WIDTH, outcomeTitle } from './graph'
import { workflowStateLabel } from '@/utils/displayLabels'
const props = defineProps<{ graph: WorkflowGraph; layout: WorkflowLayout; selected?: string; readonly?: boolean; movable?: boolean; connecting?: string; roleNames?: Record<string, string>; states?: Record<string, string> }>()
const emit = defineEmits<{ select: [id: string]; edge: [id: string]; connect: [id: string]; layout: [layout: WorkflowLayout]; remove: [id: string]; cancel: [] }>()
const viewport = ref<HTMLElement>(), local = ref(clone(props.layout))
watch(() => props.layout, layout => { local.value = clone(layout) }, { deep: true })
const defaults = computed(() => autoLayout(props.graph))
const point = (id: string): WorkflowPoint => local.value.positions[id] ?? defaults.value[id] ?? { x: 0, y: 0 }
const bounds = computed(() => {
  const points = props.graph.nodes.map(n => point(n.id))
  const x = Math.min(0, ...points.map(p => p.x)), y = Math.min(0, ...points.map(p => p.y))
  return { x, y, width: Math.max(300, ...points.map(p => p.x + NODE_WIDTH)) - x, height: Math.max(200, ...points.map(p => p.y + NODE_HEIGHT)) - y }
})
const paths = computed(() => props.graph.edges.map(edge => {
  const from = point(edge.from), to = point(edge.to), x1 = from.x + NODE_WIDTH / 2, y1 = from.y + NODE_HEIGHT, x2 = to.x + NODE_WIDTH / 2, y2 = to.y
  return { ...edge, path: `M${x1},${y1} C${x1},${y1 + 40} ${x2},${y2 - 40} ${x2},${y2}`, labelX: (x1 + x2) / 2, labelY: (y1 + y2) / 2 }
}))
let drag: { id?: string; x: number; y: number; point: WorkflowPoint; moved: boolean; pointer: number } | null = null
let ignoreClick = false
function begin(event: PointerEvent, id?: string) {
  if (event.button !== 0 || (event.target as HTMLElement).closest('button, input, select')) return
  if (id && props.readonly && !props.movable) { event.stopPropagation(); return }
  if (id) emit('select', id)
  ignoreClick = false
  drag = { id, x: event.clientX, y: event.clientY, point: id ? { ...point(id) } : { x: local.value.x, y: local.value.y }, moved: false, pointer: event.pointerId }
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  event.preventDefault(); event.stopPropagation()
}
function move(event: PointerEvent) {
  if (!drag) return
  const dx = event.clientX - drag.x, dy = event.clientY - drag.y
  if (Math.abs(dx) + Math.abs(dy) < 3) return
  drag.moved = true
  if (drag.id) local.value.positions[drag.id] = { x: drag.point.x + dx / local.value.zoom, y: drag.point.y + dy / local.value.zoom }
  else { local.value.x = drag.point.x + dx; local.value.y = drag.point.y + dy }
}
function end() { ignoreClick = drag?.moved ?? false; if (drag?.moved) emit('layout', clone(local.value)); drag = null }
function choose(id: string) { if (ignoreClick) { ignoreClick = false; return }; props.connecting ? emit('connect', id) : emit('select', id) }
function scale(amount: number) {
  const zoom = Math.max(.1, Math.min(4, local.value.zoom * amount)), width = viewport.value?.clientWidth ?? 800, height = viewport.value?.clientHeight ?? 600
  const ratio = zoom / local.value.zoom
  local.value = { ...local.value, zoom, x: width / 2 - (width / 2 - local.value.x) * ratio, y: height / 2 - (height / 2 - local.value.y) * ratio }
  emit('layout', clone(local.value))
}
function fit() {
  const width = viewport.value?.clientWidth || 800, height = viewport.value?.clientHeight || 600, b = bounds.value
  const zoom = Math.max(.1, Math.min(1.25, (width - 80) / b.width, (height - 100) / b.height))
  emit('layout', { ...clone(local.value), zoom, x: (width - b.width * zoom) / 2 - b.x * zoom, y: (height - b.height * zoom) / 2 - b.y * zoom })
}
function keys(event: KeyboardEvent, id: string) {
  if (event.target !== event.currentTarget) return
  if (event.key === 'Escape') { emit('cancel'); return }
  if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); props.connecting ? emit('connect', id) : emit('select', id); return }
  if (props.readonly && !props.movable) return
  if (event.key === 'Delete' || event.key === 'Backspace') { if (!props.readonly) { event.preventDefault(); emit('remove', id) }; return }
  const shift: Record<string, WorkflowPoint> = { ArrowLeft: { x: -24, y: 0 }, ArrowRight: { x: 24, y: 0 }, ArrowUp: { x: 0, y: -24 }, ArrowDown: { x: 0, y: 24 } }
  const delta = shift[event.key]
  if (delta) { event.preventDefault(); const p = point(id); emit('layout', { ...clone(local.value), positions: { ...local.value.positions, [id]: { x: p.x + delta.x, y: p.y + delta.y } } }) }
}
defineExpose({ fit })
</script>
<template>
  <section ref="viewport" class="workflow-canvas" aria-label="流程画布" @pointerdown="begin($event)" @pointermove="move" @pointerup="end" @pointercancel="end" @wheel.ctrl.prevent="scale($event.deltaY < 0 ? 1.1 : 1 / 1.1)" @keydown.esc="emit('cancel')">
    <div class="workflow-canvas-world" :style="{ transform: `translate(${local.x}px, ${local.y}px) scale(${local.zoom})` }">
      <svg class="workflow-wires" width="1" height="1" aria-label="节点连接">
        <g v-for="edge in paths" :key="edge.id">
          <path :d="edge.path" class="workflow-wire-hit" tabindex="0" role="button" :aria-label="`编辑连接：${graph.nodes.find(n => n.id === edge.from)?.title} 到 ${graph.nodes.find(n => n.id === edge.to)?.title}`" @pointerdown.stop @click.stop="emit('edge', edge.id)" @keydown.enter="emit('edge', edge.id)" />
          <path :d="edge.path" class="workflow-wire" />
          <text v-if="edge.outcome" :x="edge.labelX" :y="edge.labelY - 8" class="workflow-edge-label">{{ outcomeTitle(graph.nodes.find(n => n.id === edge.from), edge.outcome) }}</text>
        </g>
      </svg>
      <article v-for="(node, index) in graph.nodes" :key="node.id" class="workflow-node" :class="{ selected: selected === node.id, 'connect-source': connecting === node.id }" :style="{ left: `${point(node.id).x}px`, top: `${point(node.id).y}px` }" tabindex="0" :aria-label="`${node.title}，${node.kind === 'HUMAN' ? '人工节点' : node.kind === 'SYSTEM' ? '程序节点' : '工作节点'}`" @pointerdown="begin($event, node.id)" @click.stop="choose(node.id)" @keydown="keys($event, node.id)">
        <header><span class="workflow-node-icon"><Icon :icon="node.kind === 'HUMAN' ? 'lucide:user-round-check' : node.kind === 'SYSTEM' ? 'lucide:shield-check' : ['free.write', 'source.test-write'].includes(node.moduleId || '') ? 'lucide:code-xml' : 'lucide:scan-text'" /></span><strong>{{ node.title || `节点 ${index + 1}` }}</strong></header>
        <p>{{ node.kind === 'HUMAN' ? '人工确认' : node.kind === 'SYSTEM' ? '程序执行' : node.roleId ? roleNames?.[node.roleId] || '已配置角色' : '待选择角色' }}</p>
        <footer><span v-if="states" class="workflow-node-state" :data-state="states[node.id]">{{ workflowStateLabel(states[node.id] || 'PENDING') }}</span><span v-else>{{ node.outputs.length }} 项交付物</span><span v-if="node.pauseAfter && node.kind !== 'HUMAN'"><Icon icon="lucide:pause" />执行后暂停</span></footer>
        <button v-if="!readonly" class="workflow-port" :aria-label="`从${node.title}连接后续节点`" @pointerdown.stop @click.stop="emit('connect', node.id)"><Icon icon="lucide:plus" width="14" /></button>
      </article>
    </div>
    <div v-if="!graph.nodes.length" class="workflow-canvas-empty"><Icon icon="lucide:workflow" width="40" /><h2>从一个工作节点开始</h2><p>添加角色与任务，再连接前后顺序。</p></div>
    <p v-if="connecting" class="workflow-connecting" role="status">选择后续节点 · Esc 取消</p>
    <div class="workflow-canvas-controls" @pointerdown.stop><button aria-label="缩小画布" @click="scale(1 / 1.2)">−</button><span>{{ Math.round(local.zoom * 100) }}%</span><button aria-label="放大画布" @click="scale(1.2)">＋</button><button @click="fit"><Icon icon="lucide:scan" />适应画布</button></div>
    <svg v-if="graph.nodes.length" class="workflow-minimap" :viewBox="`${bounds.x - 20} ${bounds.y - 20} ${bounds.width + 40} ${bounds.height + 40}`" aria-label="流程缩略图" role="img"><rect v-for="node in graph.nodes" :key="node.id" :x="point(node.id).x" :y="point(node.id).y" :width="NODE_WIDTH" :height="NODE_HEIGHT" rx="12" /></svg>
  </section>
</template>
