<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ content: string }>()
const documentRoot = ref<HTMLElement>()
let renderVersion = 0
let diagramSequence = 0
let mermaidPromise: Promise<typeof import('mermaid')['default']> | undefined

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

async function renderMermaidDiagrams() {
  const version = ++renderVersion
  await nextTick()
  if (!documentRoot.value || version !== renderVersion) return

  const blocks = [...documentRoot.value.querySelectorAll<HTMLElement>('pre > code.language-mermaid')]
  if (blocks.length === 0) return
  const mermaid = await loadMermaid()
  for (const code of blocks) {
    if (version !== renderVersion || !code.parentElement) return
    const source = code.textContent?.trim()
    if (!source) continue

    const frame = document.createElement('figure')
    frame.className = 'markdown-mermaid'
    frame.setAttribute('aria-label', 'Mermaid 流程图')
    try {
      const id = `loopper-mermaid-${++diagramSequence}`
      const { svg, bindFunctions } = await mermaid.render(id, source)
      if (version !== renderVersion) return
      frame.innerHTML = DOMPurify.sanitize(normalizeMermaidSvg(svg), {
        USE_PROFILES: { html: true, svg: true, svgFilters: true },
      })
      code.parentElement.replaceWith(frame)
      bindFunctions?.(frame)
    } catch {
      frame.classList.add('markdown-mermaid-error')
      frame.textContent = '流程图语法无法渲染，已保留原始 Mermaid 文本。'
      code.parentElement.before(frame)
    }
  }
}

watch(() => props.content, renderMermaidDiagrams, { flush: 'post' })
onMounted(renderMermaidDiagrams)
</script>

<template>
  <div
    ref="documentRoot"
    class="markdown-document"
    aria-label="Markdown 文档"
    v-html="renderedHtml"
  />
</template>

<style scoped>
.markdown-document { color: var(--color-text-primary); font-size: 13px; line-height: 1.72; overflow-wrap: anywhere; }
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
.markdown-document :deep(.markdown-mermaid svg) { display: block; max-width: 100%; height: auto; }
.markdown-document :deep(.markdown-mermaid-error) { display: block; padding: 9px 12px; border-color: rgb(245 158 11 / 34%); background: rgb(245 158 11 / 8%); color: var(--color-session-warning); font-size: 11px; }
</style>
