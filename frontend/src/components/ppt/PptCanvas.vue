<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PptDeck, PptSlide, PptElement } from '@/types/domain'
import { pptElementLabel } from '@/utils/displayLabels'
const props = defineProps<{ deck: PptDeck; slide: PptSlide; selected: string; preview?: string; previewRevision?: number; revision: number; disabled?: boolean }>()
const emit = defineEmits<{ select: [id: string]; patch: [id: string, patch: Partial<PptElement>, revision: number]; remove: [id: string] }>()
const surface = ref<HTMLElement>(), ghost = ref<{ id: string; x: number; y: number; width: number; height: number }>()
let drag: { id: string; startX: number; startY: number; x: number; y: number; width: number; height: number; scale: number; resize: boolean; pointer: number; revision: number } | undefined
const theme = computed(() => props.deck.theme)
function style(element: PptElement) { const box = ghost.value?.id === element.id ? ghost.value : element; return { left: `${100 * box.x / props.deck.width}%`, top: `${100 * box.y / props.deck.height}%`, width: `${100 * box.width / props.deck.width}%`, height: `${100 * box.height / props.deck.height}%`, transform: `rotate(${element.rotation || 0}deg)` } }
function start(event: PointerEvent, element: PptElement, resize = false) {
  emit('select', element.id)
  // Pointer capture prevents the browser's default focus; keep arrow-key editing
  // available immediately after selecting or resizing an object with the mouse.
  ;(event.currentTarget as HTMLElement).closest('button')?.focus({ preventScroll: true })
  if (props.disabled || props.slide.locked || element.locked || event.button !== 0 || !surface.value) return
  event.preventDefault(); event.stopPropagation()
  drag = { id: element.id, startX: event.clientX, startY: event.clientY, x: element.x, y: element.y, width: element.width, height: element.height, scale: surface.value.getBoundingClientRect().width / props.deck.width, resize, pointer: event.pointerId, revision: props.revision }
  ghost.value = { id: element.id, x: element.x, y: element.y, width: element.width, height: element.height }
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}
function move(event: PointerEvent) { if (!drag || drag.pointer !== event.pointerId || !ghost.value) return; const dx = (event.clientX - drag.startX) / drag.scale, dy = (event.clientY - drag.startY) / drag.scale; ghost.value = { id: drag.id, x: drag.resize ? drag.x : Math.round(drag.x + dx), y: drag.resize ? drag.y : Math.round(drag.y + dy), width: drag.resize ? Math.max(12, Math.round(drag.width + dx)) : drag.width, height: drag.resize ? Math.max(12, Math.round(drag.height + dy)) : drag.height } }
function stop(event: PointerEvent) { if (!drag || drag.pointer !== event.pointerId) return; const saved = drag, box = ghost.value; drag = undefined; ghost.value = undefined; if (box && (box.x !== saved.x || box.y !== saved.y || box.width !== saved.width || box.height !== saved.height)) emit('patch', saved.id, { x: box.x, y: box.y, width: box.width, height: box.height }, saved.revision) }
function cancel() { drag = undefined; ghost.value = undefined }
function keyboard(event: KeyboardEvent, element: PptElement) { if (props.disabled || props.slide.locked || element.locked) return; const step = event.shiftKey ? 10 : 1; const direction: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }; const delta = direction[event.key]; if (delta) { event.preventDefault(); emit('patch', element.id, { x: element.x + delta[0], y: element.y + delta[1] }, props.revision) } else if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); emit('remove', element.id) } }
</script>
<template>
  <div class="ppt-canvas-wrap">
    <p v-if="preview && previewRevision !== revision" class="ppt-notice">预览来自版本 {{ previewRevision }}，当前草稿为版本 {{ revision }}。生成预览可查看最新效果。</p>
    <p v-else-if="!preview" class="ppt-muted">当前为对象位置示意，生成预览后查看实际排版。</p>
    <div ref="surface" class="ppt-canvas" :data-theme="theme" :style="{ aspectRatio: `${deck.width} / ${deck.height}` }" aria-label="幻灯片画布" @click.self="emit('select', '')" @pointermove="move" @pointerup="stop" @pointercancel="cancel">
      <img v-if="preview" :src="preview" alt="当前幻灯片实际预览" class="ppt-preview-image" draggable="false" />
      <button v-for="element in slide.elements" :key="element.id" type="button" :class="['ppt-canvas-object', { selected: selected === element.id, dragging: ghost?.id === element.id, placeholder: !preview }]" :style="style(element)" :aria-label="`${pptElementLabel(element.type)}：${element.text?.slice(0, 30) || pptElementLabel(element.type)}`" :aria-pressed="selected === element.id" @click.stop="emit('select', element.id)" @pointerdown="start($event, element)" @keydown="keyboard($event, element)">
        <span v-if="!preview">{{ element.text || pptElementLabel(element.type) }}</span>
        <span v-if="selected === element.id && !element.locked && !slide.locked && !disabled" class="ppt-resize-handle" role="button" aria-label="拖动调整对象大小" @pointerdown.stop="start($event, element, true)" />
      </button>
      <span v-if="!slide.elements.length" class="ppt-canvas-empty">添加对象，或让 PPT 助手制作这一页</span>
    </div>
    <small class="ppt-muted">{{ slide.locked ? '本页已锁定' : '选中对象后可拖动、缩放或使用方向键移动；按住 Shift 每次移动 10 点。' }}</small>
  </div>
</template>
