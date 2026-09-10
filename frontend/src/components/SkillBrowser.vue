<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import type { SkillDocument, SkillSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ projectId: string }>()
const skills = ref<SkillSummary[]>([])
const query = ref('')
const loading = ref(false)
const error = ref('')
const complete = ref(true)
const checkedAt = ref('')
const selected = ref<SkillSummary>()
const document = ref<SkillDocument>()
const documentLoading = ref(false)
const documentError = ref('')
const raw = ref(false)
let generation = 0
let documentGeneration = 0
const visible = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return skills.value.filter(skill => `${skill.name} ${skill.description}`.toLocaleLowerCase().includes(needle))
})

async function load() {
  const request = ++generation
  ++documentGeneration
  skills.value = []; selected.value = undefined; document.value = undefined
  documentLoading.value = false; documentError.value = ''; checkedAt.value = ''
  loading.value = true; error.value = ''; complete.value = true
  try {
    const result = await api.getSkills(props.projectId)
    if (request !== generation) return
    skills.value = result.skills; complete.value = result.complete; checkedAt.value = result.checkedAt
  } catch (cause) {
    if (request === generation) error.value = userFacingError(cause, '无法读取 Skill，请检查运行环境后重试')
  } finally { if (request === generation) loading.value = false }
}

async function open(skill: SkillSummary) {
  const request = ++documentGeneration
  selected.value = skill; document.value = undefined; documentError.value = ''
  documentLoading.value = true; raw.value = false
  try {
    const result = await api.getSkillDocument(props.projectId, skill.name)
    if (request === documentGeneration) document.value = result
  } catch (cause) {
    if (request === documentGeneration) documentError.value = userFacingError(cause, '文档读取失败，请重试')
  } finally { if (request === documentGeneration) documentLoading.value = false }
}

watch(() => props.projectId, () => { query.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { ++generation; ++documentGeneration })
</script>

<template>
  <section aria-label="Skill 浏览器">
    <div class="skill-toolbar">
      <el-input v-model="query" aria-label="搜索 Skill" placeholder="搜索 Skill 名称或说明" clearable />
      <span>{{ skills.length }} 个 Skill</span>
      <el-button :loading="loading" @click="load"><Icon icon="lucide:refresh-cw" aria-hidden="true" />刷新 Skill</el-button>
    </div>
    <p class="skill-hint">查看 OpenCode 在当前范围识别到的 Skill，点击阅读 Markdown 文档。</p>
    <p v-if="error" class="skill-notice" role="alert">{{ error }} <el-button link @click="load">重试</el-button></p>
    <p v-if="!complete" class="skill-notice" role="status">Skill 数量超过单次展示上限，当前为部分结果。</p>
    <p v-if="loading" role="status">正在读取 Skill…</p>
    <div v-else-if="!error && !visible.length" class="card empty-state"><div><Icon icon="lucide:book-open" /><strong>{{ query ? '没有匹配的 Skill' : '当前范围暂无 Skill' }}</strong><p>{{ query ? '尝试其他名称或说明。' : '在 OpenCode 中配置 Skill 后，刷新列表。' }}</p></div></div>
    <div v-else-if="!error" class="skill-layout">
      <div class="skill-list" aria-label="Skill 列表">
        <button v-for="skill in visible" :key="skill.name" type="button" :class="['card skill-card', { selected: selected?.name === skill.name }]" :aria-pressed="selected?.name === skill.name" @click="open(skill)">
          <span class="skill-name"><Icon icon="lucide:book-open" aria-hidden="true" /><strong>{{ skill.name }}</strong><Icon icon="lucide:chevron-right" aria-hidden="true" /></span>
          <span class="skill-description">{{ skill.description || '未提供说明' }}</span>
        </button>
      </div>
      <section class="card skill-document" aria-label="Skill 文档" :aria-busy="documentLoading">
        <div v-if="!selected" class="empty-state"><div><Icon icon="lucide:file-text" /><strong>选择一个 Skill</strong><p>在这里查看使用说明与完整 Markdown 正文。</p></div></div>
        <template v-else>
          <header><div><p class="eyebrow">Skill 文档</p><h2>{{ selected.name }}</h2></div><el-button v-if="document" :aria-pressed="raw" @click="raw = !raw">{{ raw ? '查看预览' : '查看 Markdown 源文' }}</el-button></header>
          <p v-if="selected.location" class="skill-location">{{ selected.location }}</p>
          <p v-if="documentLoading" role="status">正在读取文档…</p>
          <p v-else-if="documentError" class="skill-notice" role="alert">{{ documentError }} <el-button link @click="open(selected)">重试</el-button></p>
          <template v-else-if="document">
            <p v-if="!document.content" class="skill-hint">此 Skill 的 Markdown 正文为空。</p>
            <pre v-else-if="raw" class="skill-source" tabindex="0" aria-label="Markdown 源文">{{ document.content }}</pre>
            <MarkdownDocument v-else :content="document.content" />
          </template>
        </template>
      </section>
    </div>
    <p v-if="checkedAt" class="skill-hint">最近读取：{{ new Date(checkedAt).toLocaleString('zh-CN') }}</p>
  </section>
</template>

<style scoped>
.skill-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.skill-toolbar .el-input { flex: 1; min-width: 200px; }
.skill-toolbar > span, .skill-hint, .skill-location { color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.skill-layout { display: grid; grid-template-columns: minmax(220px, .7fr) minmax(0, 1.5fr); gap: 18px; align-items: start; }
.skill-list { display: grid; gap: 10px; max-height: 72vh; overflow-y: auto; padding: 4px; }
.skill-card { display: grid; gap: 10px; width: 100%; padding: 18px; text-align: left; color: var(--color-text-primary); cursor: pointer; }
.skill-card:hover, .skill-card.selected { border-color: var(--color-accent-cyan); background: var(--color-bg-elevated); }
.skill-name { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.skill-name strong { flex: 1; overflow-wrap: anywhere; }
.skill-name .iconify { flex: 0 0 auto; color: var(--color-accent-cyan); }
.skill-description { color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.skill-document { min-width: 0; padding: 24px; }
.skill-document header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.skill-document h2 { margin: 0; font-size: 18px; overflow-wrap: anywhere; }
.skill-document header > div { min-width: 0; }
.skill-location { overflow-wrap: anywhere; padding-bottom: 16px; border-bottom: 1px solid var(--color-border-default); }
.skill-source { white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.8 var(--font-code); }
.skill-notice { color: var(--color-session-warning); font-size: 12px; line-height: 1.7; }
@media (max-width: 1100px) { .skill-layout { grid-template-columns: 1fr; } .skill-list { max-height: 340px; } }
@media (max-width: 640px) { .skill-document { padding: 16px; } }
</style>
