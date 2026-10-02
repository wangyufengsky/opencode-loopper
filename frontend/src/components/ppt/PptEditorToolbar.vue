<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import type { PptDeck, PptSlide, PptElement } from '@/types/domain'
import { usePptStore } from '@/stores/pptStore'
import { pptElementLabel, pptLayoutLabel } from '@/utils/displayLabels'

const props = defineProps<{
  deck: PptDeck
  slide: PptSlide
  element: PptElement | null
  disabled: boolean
}>()
const emit = defineEmits<{
  add: [type: string]
  image: []
  page: [action: 'duplicate_slide' | 'delete_slide' | 'move_slide', index?: number]
  theme: [value: string]
  properties: []
}>()
const store = usePptStore()
const layout = ref('title_content')
const toolbar = ref<HTMLElement>()
const opened = ref<'insert' | 'page' | null>(null)
watch(() => props.slide.id, () => { opened.value = null })
function outside(event: PointerEvent) {
  if (!toolbar.value?.contains(event.target as Node)) opened.value = null
}
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
function add(type: string) {
  emit('add', type)
  opened.value = null
}
function closeMenu(event: KeyboardEvent) {
  if (!opened.value) return
  event.stopPropagation()
  const active = opened.value
  opened.value = null
  toolbar.value?.querySelector<HTMLButtonElement>(`[data-menu="${active}"]`)?.focus()
}
</script>

<template>
  <div ref="toolbar" class="ppt-manual-tools" aria-label="手动编辑工具" @keydown.esc="closeMenu">
    <div class="ppt-editor-toolbar">
      <button data-menu="insert" :aria-expanded="opened === 'insert'" :disabled="disabled || slide.locked" @click="opened = opened === 'insert' ? null : 'insert'"><Icon icon="lucide:plus" />插入对象</button>
      <button data-menu="page" :aria-expanded="opened === 'page'" @click="opened = opened === 'page' ? null : 'page'"><Icon icon="lucide:layout-template" />页面设置</button>
      <span class="ppt-tool-hint">{{ slide.locked ? '页面已锁定' : '选择对象后编辑属性' }}</span>
    </div>
    <div v-if="opened === 'insert'" class="ppt-insert-menu" aria-label="插入对象类型">
      <button
        v-for="type in ['text', 'shape', 'line', 'table', 'chart']"
        :key="type"
        :disabled="disabled || slide.locked"
        @click="add(type)"
      >
        添加{{ pptElementLabel(type) }}
      </button>
      <button :disabled="disabled || slide.locked" @click="emit('image'); opened = null">
        <Icon icon="lucide:image" />
        图片
      </button>
    </div>
    <div v-if="opened === 'page'" class="ppt-page-settings" aria-label="页面设置">
      <div class="ppt-page-setting-actions">
      <button :disabled="disabled" @click="emit('properties'); opened = null"><Icon icon="lucide:sliders-horizontal" />{{ element ? '对象属性' : '页面属性' }}</button>
      <button
        :disabled="disabled"
        @click="
          store.operations([
            {
              op: 'update_slide',
              slideId: slide.id,
              patch: { locked: !slide.locked },
            },
          ])
        "
      >
        <Icon :icon="slide.locked ? 'lucide:lock-open' : 'lucide:lock'" />
        {{ slide.locked ? '解锁页面' : '锁定页面' }}
      </button>
      </div>
      <div class="ppt-page-setting-actions">
        <select v-model="layout" aria-label="页面版式" :disabled="disabled || slide.locked">
          <option v-for="value in store.capabilities?.layouts || []" :key="value" :value="value">
            {{ pptLayoutLabel(value) }}
          </option>
        </select>
        <button
          :disabled="disabled || slide.locked"
          @click="store.operations([{ op: 'apply_layout', slideId: slide.id, layout }])"
        >
          应用版式
        </button>
        <select
          :value="deck.theme"
          :disabled="disabled"
          aria-label="演示主题"
          @change="emit('theme', ($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="theme in store.capabilities?.themes || []"
            :key="theme.id"
            :value="theme.id"
          >
            {{ theme.name }}
          </option>
        </select>
        <button :disabled="disabled" @click="emit('page', 'duplicate_slide')">复制页面</button>
        <button
          :disabled="disabled || deck.slides[0]?.id === slide.id"
          @click="
            emit('page', 'move_slide', deck.slides.findIndex((item) => item.id === slide.id) - 1)
          "
        >
          上移
        </button>
        <button
          :disabled="disabled || deck.slides.at(-1)?.id === slide.id"
          @click="
            emit('page', 'move_slide', deck.slides.findIndex((item) => item.id === slide.id) + 1)
          "
        >
          下移
        </button>
        <button :disabled="disabled || slide.locked" @click="emit('page', 'delete_slide')">
          删除页面
        </button>
        </div>
        <div class="ppt-page-setting-actions">
        <button
          v-if="element"
          :disabled="disabled || !element || element.locked || slide.locked"
          @click="
            element &&
            store.operations([
              {
                op: 'move_element',
                slideId: slide.id,
                elementId: element.id,
                index: slide.elements.length - 1,
              },
            ])
          "
        >
          对象置顶
        </button>
        <button
          v-if="element"
          :disabled="disabled || !element || element.locked || slide.locked"
          @click="
            element &&
            store.operations([
              {
                op: 'move_element',
                slideId: slide.id,
                elementId: element.id,
                index: 0,
              },
            ])
          "
        >
          对象置底
        </button>
      </div>
    </div>
  </div>
</template>
