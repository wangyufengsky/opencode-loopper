<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ requirement: string; node: string; attempt: string; direction: 'inputs' | 'outputs'; name: string; path: string }>()
const emit = defineEmits<{ navigate: [path: string]; close: [] }>()
type Saved = { text: string; nextOffset: number | null; sha256: string }
const cache = new Map<string, Saved>(), body = ref<Saved | null>(null), error = ref(''), busy = ref(false)
let generation = 0, controller: AbortController | undefined
async function load(more = false) {
  if (more && (body.value?.text.length || 0) >= 2 * 1024 * 1024) return
  controller?.abort(); controller = new AbortController(); const current = ++generation, path = props.path
  busy.value = true; error.value = ''; const previous = more ? body.value : null
  try {
    const page = await workflowRuns.fileText(props.requirement, props.node, props.attempt, props.direction, props.name, path, previous?.nextOffset || 0, controller.signal)
    if (generation !== current) return
    if (page.path !== path || page.offset !== (previous?.nextOffset || 0) || previous && page.sha256 !== previous.sha256 || page.nextOffset !== null && page.nextOffset <= page.offset) throw new Error('文档分页版本不一致')
    body.value = { text: (previous?.text || '') + page.text, nextOffset: page.nextOffset, sha256: page.sha256 }; cache.set(path, body.value); if (cache.size > 8) cache.delete(cache.keys().next().value!)
  } catch (failure) { if (generation === current) error.value = userFacingError(failure, '报告正文暂时无法读取，请重试或下载。') }
  finally { if (generation === current) busy.value = false }
}
watch(() => props.path, path => { controller?.abort(); generation++; body.value = cache.get(path) || null; error.value = ''; busy.value = false; if (!body.value) void load() }, { immediate: true })
onBeforeUnmount(() => { generation++; controller?.abort() })
function resolveLink(href: string): string | null {
  if (href.startsWith('#')) return href.startsWith('#workflow-document=') ? null : href
  let decoded: string
  try { decoded = decodeURIComponent(href.split('#')[0]!) } catch { return null }
  if (/^[\/\\]|[:?\\\u0000-\u001f\u007f]/.test(decoded) || !decoded.endsWith('.md')) return null
  const parts = props.path.split('/'); parts.pop(); const minimum = props.path.includes('/') ? 1 : 0
  for (const part of decoded.split('/')) {
    if (part === '..') { if (parts.length <= minimum) return null; parts.pop() }
    else if (part && part !== '.') parts.push(part)
    else if (!part) return null
  }
  return `#workflow-document=${encodeURIComponent(parts.join('/'))}`
}
function navigate(event: MouseEvent) {
  const link = (event.target as Element).closest('a'), href = link?.getAttribute('href') || ''
  if (!href.startsWith('#workflow-document=')) return
  event.preventDefault(); emit('navigate', decodeURIComponent(href.slice('#workflow-document='.length)))
}
</script>
<template><section class="workflow-document-preview" aria-label="报告预览"><header><h5>{{ path.split('/').at(-1) }}</h5><button @click="emit('close')">关闭预览</button></header>
  <p v-if="error" role="alert">{{ error }} <button :disabled="busy" @click="load(!!body)">重试读取</button></p><p v-if="busy">读取报告…</p>
  <div v-if="body" @click="navigate"><MarkdownDocument :content="body.text" :allow-images="false" :resolve-link="resolveLink" /></div>
  <p v-if="body?.nextOffset != null && body.text.length >= 2 * 1024 * 1024">正文较长，请下载完整报告继续阅读。</p><button v-else-if="body?.nextOffset != null" :disabled="busy" @click="load(true)">继续读取报告</button>
</section></template>
<style scoped>
.workflow-document-preview { margin-top: 1rem; padding: 1rem; border: 1px solid var(--color-border-default); border-radius: var(--radius-card); overflow-wrap: anywhere; min-width: 0; }
header { display: flex; align-items: start; justify-content: space-between; gap: 1rem; flex-wrap: wrap; } h5 { margin: 0; }
</style>
