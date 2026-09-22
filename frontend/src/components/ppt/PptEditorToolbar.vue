<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { PptDeck, PptSlide, PptElement } from '@/types/domain'
import { usePptStore } from '@/stores/pptStore'
import { pptElementLabel, pptLayoutLabel } from '@/utils/displayLabels'

defineProps<{
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
}>()
const store = usePptStore()
const layout = ref('title_content')
</script>

<template>
  <div class="ppt-manual-tools" aria-label="手动编辑工具">
    <div class="ppt-editor-toolbar">
      <button
        v-for="type in ['text', 'shape', 'line', 'table', 'chart']"
        :key="type"
        :disabled="disabled || slide.locked"
        @click="emit('add', type)"
      >
        添加{{ pptElementLabel(type) }}
      </button>
      <button :disabled="disabled || slide.locked" @click="emit('image')">
        <Icon icon="lucide:image" />
        图片
      </button>
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
    <details class="ppt-edit-more">
      <summary>
        版式与页面操作
        <Icon icon="lucide:chevron-down" />
      </summary>
      <div class="ppt-editor-toolbar">
        <select v-model="layout" aria-label="页面版式">
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
        <button
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
    </details>
  </div>
</template>
