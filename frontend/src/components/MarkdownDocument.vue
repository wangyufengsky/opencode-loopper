<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const props = withDefaults(defineProps<{
  content: string
  collapsible?: boolean
  collapsedLines?: number
}>(), {
  collapsible: false,
  collapsedLines: 3,
})
const documentRoot = ref<HTMLElement>()
const expanded = ref(false)
const overflowing = ref(props.collapsible)
let renderVersion = 0
let diagramSequence = 0
let mermaidPromise: Promise<typeof import('mermaid')['default']> | undefined
let mermaidObserver: IntersectionObserver | undefined
const mermaidJobs = new Map<Element, () => Promise<void>>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: true,
})

markdown.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  tokens[index]!.attrSet('target', '_blank')
  tokens[index]!.attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, index, options)
}

const renderedHtml = computed(() => DOMPurify.sanitize(markdown.render(props.content), {
  ADD_ATTR: ['target'],
  USE_PROFILES: { html: true },
}))

function loadMermaid() {
  mermaidPromise ??= import('mermaid').then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'dark',
      flowchart: { htmlLabels: false },
      themeVariables: {
        background: '#0b1221',
        primaryColor: '#152440',
        primaryTextColor: '#e6edf8',
        primaryBorderColor: '#3b82f6',
        lineColor: '#8293ad',
        secondaryColor: '#182c3a',
        tertiaryColor: '#171f35',
        fontFamily: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
      },
    })
    return mermaid
  })
  return mermaidPromise
}

/** Mermaid may use SVG foreignObject labels, which secure SVG sanitizers remove.
 * Convert those labels to plain SVG text first so node names remain visible
 * without admitting embedded HTML from model-authored diagram source. */
function normalizeMermaidSvg(svg: string) {
  // Mermaid's SVG may contain HTML-style void elements inside foreignObject.
  // XML requires them to be self-closing, otherwise DOMParser returns a
  // parsererror document instead of the chart. Normalize only known void tags;
  // the resulting SVG is still sanitized before insertion into the page.
  const xmlSafe = svg.replace(/<(br|hr|img|input|meta|link)(\b[^>]*)>/gi, (tag, name, attributes) => (
    tag.endsWith('/>') ? tag : `<${name}${attributes}/>`
  ))
  const parsed = new DOMParser().parseFromString(xmlSafe, 'image/svg+xml')
  if (parsed.querySelector('parsererror')) throw new Error('Mermaid returned malformed SVG')
  for (const foreignObject of [...parsed.querySelectorAll('foreignObject')]) {
    const textContent = foreignObject.textContent?.replace(/\s+/g, ' ').trim()
    if (!textContent) {
      foreignObject.remove()
      continue
    }
    const x = Number.parseFloat(foreignObject.getAttribute('x') ?? '0') || 0
    const y = Number.parseFloat(foreignObject.getAttribute('y') ?? '0') || 0
    const width = Number.parseFloat(foreignObject.getAttribute('width') ?? '0') || 0
    const height = Number.parseFloat(foreignObject.getAttribute('height') ?? '0') || 0
    const label = parsed.createElementNS('http://www.w3.org/2000/svg', 'text')
    label.setAttribute('class', 'mermaid-safe-label')
    label.setAttribute('x', String(x + width / 2))
    label.setAttribute('y', String(y + height / 2))
    label.setAttribute('text-anchor', 'middle')
    label.setAttribute('dominant-baseline', 'middle')
    label.textContent = textContent
    foreignObject.replaceWith(label)
  }
  return new XMLSerializer().serializeToString(parsed.documentElement)
}

async function renderMermaidFrame(frame: HTMLElement, source: string, version: number) {
  const mermaid = await loadMermaid()
  if (version !== renderVersion || !documentRoot.value?.contains(frame)) return
  try {
    const id = `loopper-mermaid-${++diagramSequence}`
    const { svg, bindFunctions } = await mermaid.render(id, source)
    if (version !== renderVersion || !documentRoot.value?.contains(frame)) return
    frame.classList.remove('markdown-mermaid-pending')
    frame.setAttribute('aria-label', 'Mermaid 流程图')
    frame.innerHTML = DOMPurify.sanitize(normalizeMermaidSvg(svg), {
      USE_PROFILES: { html: true, svg: true, svgFilters: true },
    })
    bindFunctions?.(frame)
  } catch {
    frame.classList.remove('markdown-mermaid-pending')
    frame.classList.add('markdown-mermaid-error')
    frame.textContent = '流程图语法无法渲染，请检查 Mermaid 文本。'
  }
  await measureOverflow()
}

function observeMermaidFrame(frame: HTMLElement, source: string, version: number) {
  const render = () => renderMermaidFrame(frame, source, version)
  if (typeof IntersectionObserver === 'undefined') {
    void render()
    return
  }
  mermaidObserver ??= new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue
      const job = mermaidJobs.get(entry.target)
      mermaidObserver?.unobserve(entry.target)
      mermaidJobs.delete(entry.target)
      if (job) void job()
    }
  }, { rootMargin: '240px 0px' })
  mermaidJobs.set(frame, render)
  mermaidObserver.observe(frame)
}

async function renderMermaidDiagrams() {
  const version = ++renderVersion
  mermaidObserver?.disconnect()
  mermaidJobs.clear()
  await nextTick()
  if (!documentRoot.value || version !== renderVersion) return

  const blocks = [...documentRoot.value.querySelectorAll<HTMLElement>('pre > code.language-mermaid')]
  for (const code of blocks) {
    if (!code.parentElement) continue
    const source = code.textContent?.trim()
    if (!source) continue
    const frame = document.createElement('figure')
    frame.className = 'markdown-mermaid markdown-mermaid-pending'
    frame.setAttribute('aria-label', 'Mermaid 流程图，接近可视区域时加载')
    frame.textContent = '流程图将在滚动到此处时加载…'
    code.parentElement.replaceWith(frame)
    observeMermaidFrame(frame, source, version)
  }
}

async function measureOverflow() {
  await nextTick()
  if (!props.collapsible || expanded.value || !documentRoot.value) {
    if (!props.collapsible) overflowing.value = false
    return
  }
  // Measure while the three-line cap is active. Measuring the fully expanded
  // element would make scrollHeight equal clientHeight and hide the control.
  overflowing.value = true
  await nextTick()
  overflowing.value = documentRoot.value.scrollHeight > documentRoot.value.clientHeight + 1
}

function toggleExpanded() {
  expanded.value = !expanded.value
}

watch(() => props.content, async () => {
  await renderMermaidDiagrams()
  await measureOverflow()
}, { flush: 'post' })
watch(() => props.collapsible, measureOverflow, { flush: 'post' })
onMounted(async () => {
  await renderMermaidDiagrams()
  await measureOverflow()
})
onBeforeUnmount(() => {
  renderVersion += 1
  mermaidObserver?.disconnect()
  mermaidJobs.clear()
})
</script>

<template>
  <div class="markdown-output">
    <div
      ref="documentRoot"
      :class="['markdown-document', { 'is-collapsed': collapsible && overflowing && !expanded }]"
      :style="{ '--collapsed-lines': collapsedLines }"
      aria-label="Markdown 文档"
      v-html="renderedHtml"
    />
    <button
      v-if="collapsible && overflowing"
      type="button"
      class="markdown-expand-button"
      :aria-expanded="expanded"
      @click="toggleExpanded"
    >
      {{ expanded ? '收起输出' : '展开完整输出' }}
      <span aria-hidden="true">{{ expanded ? '↑' : '↓' }}</span>
    </button>
  </div>
</template>

<style scoped>
.markdown-output { min-width: 0; }
.markdown-document { color: var(--color-text-primary); font-size: 13px; line-height: 1.72; overflow-wrap: anywhere; }
.markdown-document.is-collapsed { max-height: calc(var(--collapsed-lines) * 1.72em); overflow: hidden; }
.markdown-expand-button { display: inline-flex; align-items: center; gap: 5px; margin-top: 8px; padding: 4px 0; border: 0; background: transparent; color: var(--color-accent-cyan); font: 600 11px/1.4 var(--font-ui); cursor: pointer; }
.markdown-expand-button:hover { color: #e0f2fe; }
.markdown-expand-button:focus-visible { border-radius: 4px; outline: 2px solid var(--color-accent-cyan); outline-offset: 3px; }
.markdown-document :deep(> :first-child) { margin-top: 0; }
.markdown-document :deep(> :last-child) { margin-bottom: 0; }
.markdown-document :deep(h1),
.markdown-document :deep(h2),
.markdown-document :deep(h3),
.markdown-document :deep(h4) { color: #f5f8ff; font-weight: 720; line-height: 1.28; letter-spacing: -.02em; }
.markdown-document :deep(h1) { margin: 0 0 18px; padding-bottom: 13px; border-bottom: 1px solid rgb(96 165 250 / 22%); font-size: 24px; }
.markdown-document :deep(h2) { margin: 27px 0 11px; font-size: 18px; }
.markdown-document :deep(h3) { margin: 21px 0 9px; color: #dfe8f8; font-size: 15px; }
.markdown-document :deep(h4) { margin: 18px 0 7px; font-size: 13px; }
.markdown-document :deep(p) { margin: 9px 0; }
.markdown-document :deep(ul), .markdown-document :deep(ol) { margin: 10px 0; padding-left: 24px; }
.markdown-document :deep(li) { margin: 5px 0; padding-left: 2px; }
.markdown-document :deep(li::marker) { color: var(--color-accent-cyan); }
.markdown-document :deep(strong) { color: #fff; font-weight: 720; }
.markdown-document :deep(a) { color: #7dd3fc; text-decoration: underline; text-decoration-color: rgb(125 211 252 / 35%); text-underline-offset: 3px; }
.markdown-document :deep(a:hover) { color: var(--color-accent-cyan); text-decoration-color: currentcolor; }
.markdown-document :deep(blockquote) { margin: 16px 0; padding: 10px 14px; border-left: 3px solid var(--color-accent-ai); border-radius: 0 8px 8px 0; background: rgb(139 92 246 / 8%); color: var(--color-text-secondary); }
.markdown-document :deep(blockquote p) { margin: 0; }
.markdown-document :deep(code) { padding: 2px 5px; border: 1px solid rgb(125 211 252 / 14%); border-radius: 5px; background: rgb(4 9 18 / 75%); color: #bae6fd; font-family: var(--font-code); font-size: .9em; }
.markdown-document :deep(pre) { max-width: 100%; margin: 15px 0; padding: 14px 16px; overflow: auto; border: 1px solid #1e304d; border-radius: 9px; background: #070d18; box-shadow: inset 0 1px rgb(255 255 255 / 2%); }
.markdown-document :deep(pre code) { padding: 0; border: 0; background: transparent; color: #c5d3e7; font-size: 11px; line-height: 1.65; white-space: pre; }
.markdown-document :deep(hr) { height: 1px; margin: 24px 0; border: 0; background: var(--color-border-default); }
.markdown-document :deep(table) { display: block; width: 100%; margin: 16px 0; overflow-x: auto; border-spacing: 0; border-collapse: collapse; font-size: 12px; }
.markdown-document :deep(th), .markdown-document :deep(td) { min-width: 110px; padding: 9px 11px; border: 1px solid var(--color-border-default); text-align: left; vertical-align: top; }
.markdown-document :deep(th) { color: #f1f5f9; background: rgb(59 130 246 / 10%); font-weight: 680; }
.markdown-document :deep(tr:nth-child(even) td) { background: rgb(7 13 24 / 35%); }
.markdown-document :deep(.markdown-mermaid) { display: grid; place-items: center; max-width: 100%; margin: 18px 0; padding: 18px 12px; overflow: auto; border: 1px solid rgb(34 211 238 / 19%); border-radius: 11px; background: radial-gradient(circle at 50% 0, rgb(59 130 246 / 10%), transparent 55%), #090f1c; }
.markdown-document :deep(.markdown-mermaid-pending) { min-height: 120px; color: var(--color-text-tertiary); font: 11px/1.5 var(--font-ui); }
.markdown-document :deep(.markdown-mermaid svg) { display: block; max-width: 100%; height: auto; }
.markdown-document :deep(.markdown-mermaid-error) { display: block; padding: 9px 12px; border-color: rgb(245 158 11 / 34%); background: rgb(245 158 11 / 8%); color: var(--color-session-warning); font-size: 11px; }
</style>
