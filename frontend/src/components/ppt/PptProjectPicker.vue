<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { pptApi } from '@/api/ppt'
import type { PptProjectChoice } from '@/types/ppt'

const project = defineModel<PptProjectChoice | null>({ required: true })
const props = defineProps<{ disabled?: boolean }>()
const open = ref(false)
const search = ref('')
const projects = ref<PptProjectChoice[]>([])
const cursor = ref<string | null>(null)
const busy = ref(false)
const error = ref('')
let sequence = 0

async function load(more = false) {
  const ticket = ++sequence
  busy.value = true
  error.value = ''
  try {
    const page = await pptApi.projects(search.value.trim(), more ? cursor.value || '' : '')
    if (ticket !== sequence) return
    projects.value = more ? [...projects.value, ...page.items] : page.items
    cursor.value = page.nextCursor || null
  } catch {
    if (ticket === sequence) error.value = '项目暂时无法读取，请重试。也可以不关联项目继续。'
  } finally {
    if (ticket === sequence) busy.value = false
  }
}
function select(value: PptProjectChoice | null) {
  if (props.disabled) return
  project.value = value
  open.value = false
}
watch(open, (value) => { if (value) void load() })
watch(() => props.disabled, (value) => { if (value) open.value = false })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <div class="ppt-project-picker" @keydown.esc.stop="open = false">
    <button
      type="button"
      class="ppt-project-trigger"
      :disabled="disabled"
      :aria-expanded="open"
      aria-controls="ppt-project-choices"
      :aria-label="project ? `关联项目：${project.name}` : '选择项目（可选）'"
      @click="open = !open"
    >
      <Icon icon="lucide:folder-open" />
      <span>{{ project?.name || '选择项目（可选）' }}</span>
      <Icon icon="lucide:chevron-down" />
    </button>
    <div v-if="open" id="ppt-project-choices" class="ppt-project-popover" aria-label="选择关联项目">
      <div class="ppt-project-search">
        <input v-model="search" aria-label="搜索关联项目" placeholder="搜索项目" @keydown.enter.prevent="load()" />
        <button type="button" :disabled="busy" @click="load()">搜索</button>
      </div>
      <button type="button" class="ppt-project-choice" :aria-pressed="!project" @click="select(null)">
        <span>不关联项目</span><small>使用你的要求和附件</small>
      </button>
      <p v-if="error" role="alert" class="ppt-inline-error">{{ error }} <button type="button" @click="load()">重试</button></p>
      <p v-if="busy" role="status" class="ppt-muted">正在读取项目…</p>
      <div class="ppt-project-options">
        <button
          v-for="choice in projects" :key="choice.id" type="button" class="ppt-project-choice"
          :aria-pressed="choice.id === project?.id" @click="select(choice)"
        >
          <span>{{ choice.name }}</span><Icon v-if="choice.id === project?.id" icon="lucide:check" />
        </button>
      </div>
      <p v-if="!busy && !error && !projects.length" class="ppt-muted">没有找到项目，可直接开始沟通。</p>
      <button v-if="cursor" type="button" :disabled="busy" @click="load(true)">更多项目</button>
      <p class="ppt-muted">选择后，助手可查询该项目当前可用的知识库来源。</p>
      <button type="button" class="ppt-project-close" @click="open = false">收起</button>
    </div>
  </div>
</template>
