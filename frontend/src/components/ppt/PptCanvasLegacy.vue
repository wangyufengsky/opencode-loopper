<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import type { PptDeck, PptSlide, PptElement } from '@/types/domain'
import { pptElementLabel } from '@/utils/displayLabels'
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
const surface = ref<HTMLElement>(),
  ghost = ref<{
    id: string
    x: number
    y: number
    width: number
    height: number
  }>()
let drag:
  | {
      id: string
      startX: number
      startY: number
      x: number
      y: number
      width: number
      height: number
      scale: number
      resize: boolean
      pointer: number
      revision: number
    }
  | undefined
let suppressClick = false
const theme = computed(() => props.deck.theme)
const viewport = ref<HTMLElement>()
const zoom = ref(1)
const available = ref({ width: 960, height: 588 })
const fitWidth = computed(() =>
  Math.max(120, Math.min(
    available.value.width - 48,
    (available.value.height - 48) * props.deck.width / props.deck.height,
  )),
)
const canvasWidth = computed(() => fitWidth.value * zoom.value)
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!viewport.value || typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(([entry]) => {
    if (entry) available.value = { width: entry.contentRect.width, height: entry.contentRect.height }
  })
  observer.observe(viewport.value)
})
onBeforeUnmount(() => observer?.disconnect())
watch(() => [props.slide.id, props.disabled, props.revision], cancel)
watch(() => props.selected, (id) => {
  if (drag && drag.id !== id) cancel()
})

function scale(delta: number) {
  zoom.value = Math.max(0.5, Math.min(2, Math.round((zoom.value + delta) * 10) / 10))
}

function canvasKeyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    cancel()
    emit('select', '')
  } else if (event.key === '+' || event.key === '=') {
    event.preventDefault()
    scale(0.1)
  } else if (event.key === '-') {
    event.preventDefault()
    scale(-0.1)
  } else if (event.key === '0') {
    event.preventDefault()
    zoom.value = 1
  }
}

function style(element: PptElement) {
  const box = ghost.value?.id === element.id ? ghost.value : element
  return {
    left: `${(100 * box.x) / props.deck.width}%`,
    top: `${(100 * box.y) / props.deck.height}%`,
    width: `${(100 * box.width) / props.deck.width}%`,
    height: `${(100 * box.height) / props.deck.height}%`,
    transform: `rotate(${element.rotation || 0}deg)`,
  }
}

function start(event: PointerEvent, element: PptElement, resize = false) {
  suppressClick = false
  // Pointer capture prevents the browser's default focus; keep arrow-key editing
  // available immediately after selecting or resizing an object with the mouse.
  ;(event.currentTarget as HTMLElement).closest('button')?.focus({
    preventScroll: true,
  })
  if (
    props.disabled ||
    props.slide.locked ||
    element.locked ||
    event.button !== 0 ||
    !surface.value
  )
    return
  event.preventDefault()
  event.stopPropagation()
  drag = {
    id: element.id,
    startX: event.clientX,
    startY: event.clientY,
    x: element.x,
    y: element.y,
    width: element.width,
    height: element.height,
    scale: surface.value.getBoundingClientRect().width / props.deck.width,
    resize,
    pointer: event.pointerId,
    revision: props.revision,
  }
  ghost.value = {
    id: element.id,
    x: element.x,
    y: element.y,
    width: element.width,
    height: element.height,
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}

function move(event: PointerEvent) {
  if (!drag || drag.pointer !== event.pointerId || !ghost.value) return
  const dx = (event.clientX - drag.startX) / drag.scale,
    dy = (event.clientY - drag.startY) / drag.scale
  if (dx || dy) suppressClick = true
  ghost.value = {
    id: drag.id,
    x: drag.resize ? drag.x : Math.round(drag.x + dx),
    y: drag.resize ? drag.y : Math.round(drag.y + dy),
    width: drag.resize ? Math.max(12, Math.round(drag.width + dx)) : drag.width,
    height: drag.resize ? Math.max(12, Math.round(drag.height + dy)) : drag.height,
  }
}

function stop(event: PointerEvent) {
  if (!drag || drag.pointer !== event.pointerId) return
  const saved = drag,
    box = ghost.value
  drag = undefined
  ghost.value = undefined
  // Open contextual panels only after pointer release so a newly selected
  // object's drag uses a stable canvas size throughout the gesture.
  emit('select', saved.id)
  if (
    box &&
    (box.x !== saved.x ||
      box.y !== saved.y ||
      box.width !== saved.width ||
      box.height !== saved.height)
  )
    emit(
      'patch',
      saved.id,
      {
        x: box.x,
        y: box.y,
        width: box.width,
        height: box.height,
      },
      saved.revision,
    )
}

function cancel() {
  if (drag) suppressClick = true
  drag = undefined
  ghost.value = undefined
}

function clickSelect(event: MouseEvent, id: string) {
  if (!suppressClick || event.detail === 0) emit('select', id)
  suppressClick = false
}

function keyboard(event: KeyboardEvent, element: PptElement) {
  if (props.disabled || props.slide.locked || element.locked || props.selected !== element.id) return
  const step = event.shiftKey ? 10 : 1
  const direction: Record<string, [number, number]> = {
    ArrowLeft: [-step, 0],
    ArrowRight: [step, 0],
    ArrowUp: [0, -step],
    ArrowDown: [0, step],
  }
  const delta = direction[event.key]
  if (delta) {
    event.preventDefault()
    emit(
      'patch',
      element.id,
      event.altKey
        ? { width: Math.max(12, element.width + delta[0]), height: Math.max(12, element.height + delta[1]) }
        : { x: element.x + delta[0], y: element.y + delta[1] },
      props.revision,
    )
  } else if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    emit('remove', element.id)
  }
}
</script>
<template>
  <div class="ppt-canvas-wrap" data-canvas-runtime="vue" data-canvas-kind="ppt" @keydown="canvasKeyboard">
    <p v-if="preview && previewRevision !== revision" class="ppt-notice">
      预览来自版本 {{ previewRevision }}，当前草稿为版本 {{ revision }}。生成预览可查看最新效果。
    </p>
    <p v-else-if="!preview" class="ppt-muted">当前为对象位置示意，生成预览后查看实际排版。</p>
    <div
      ref="viewport"
      class="ppt-canvas-viewport"
      tabindex="0"
      aria-label="幻灯片画布视口"
      aria-keyshortcuts="+ - 0 Escape"
      @click.self="emit('select', '')"
    >
      <div
        class="ppt-canvas-stage"
        :style="{ width: `${canvasWidth + 48}px`, height: `${canvasWidth * deck.height / deck.width + 48}px` }"
        @click.self="emit('select', '')"
      >
        <div
          ref="surface"
          class="ppt-canvas"
          :data-theme="theme"
          :style="{ width: `${canvasWidth}px`, aspectRatio: `${deck.width} / ${deck.height}` }"
          aria-label="幻灯片画布"
          @click.self="emit('select', '')"
          @pointermove="move"
          @pointerup="stop"
          @pointercancel="cancel"
        >
          <img
            v-if="preview"
            :src="preview"
            alt="当前幻灯片实际预览"
            class="ppt-preview-image"
            draggable="false"
          />
          <button
            v-for="element in slide.elements"
            :key="element.id"
            type="button"
            :class="[
              'ppt-canvas-object',
              {
                selected: selected === element.id,
                dragging: ghost?.id === element.id,
                placeholder: !preview,
              },
            ]"
            :style="style(element)"
            :aria-label="`${pptElementLabel(element.type)}：${element.text?.slice(0, 30) || pptElementLabel(element.type)}`"
            :aria-pressed="selected === element.id"
            @click.stop="clickSelect($event, element.id)"
            @pointerdown="start($event, element)"
            @keydown="keyboard($event, element)"
          >
            <span v-if="!preview">{{ element.text || pptElementLabel(element.type) }}</span>
            <span
              v-if="selected === element.id && !element.locked && !slide.locked && !disabled"
              class="ppt-resize-handle"
              role="button"
              aria-label="拖动调整对象大小"
              @pointerdown.stop="start($event, element, true)"
            />
          </button>
          <span v-if="!slide.elements.length" class="ppt-canvas-empty">
            添加对象，或让 PPT 助手制作这一页
          </span>
        </div>
      </div>
    </div>
    <footer class="ppt-canvas-footer">
      <small class="ppt-muted">
        {{ slide.locked ? '本页已锁定' : selected
          ? (editing ? '方向键移动 · Alt + 方向键缩放 · Esc 取消选择' : '已选择对象，可向助手提出修改意见')
          : '选择对象，开始修改' }}
      </small>
      <div class="ppt-zoom-controls" aria-label="画布缩放">
        <button aria-label="缩小画布" title="缩小画布（−）" :disabled="zoom <= 0.5" @click="scale(-0.1)">
          <Icon icon="lucide:minus" />
        </button>
        <button class="ppt-zoom-value" aria-label="适应画布" title="适应画布（0）" @click="zoom = 1">
          {{ Math.round(zoom * 100) }}%
        </button>
        <button aria-label="放大画布" title="放大画布（+）" :disabled="zoom >= 2" @click="scale(0.1)">
          <Icon icon="lucide:plus" />
        </button>
      </div>
    </footer>
  </div>
</template>
