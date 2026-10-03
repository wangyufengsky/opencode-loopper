import DOMPurify from 'dompurify'
import { nextDiagramId, renderDiagram } from '@/themes/mermaid'
import type { SkinDefinition } from '@/themes/types'

export const mermaidPendingMessage = '流程图将在滚动到此处时加载…'
export const mermaidErrorMessage = '流程图语法无法渲染，请检查 Mermaid 文本。'
export interface SafeMermaidDiagram { id: string; svg: string; bindFunctions?: (element: Element) => void }

export function removeMermaidRenderArtifacts(id: string) {
  document.getElementById(`d${id}`)?.remove()
  document.getElementById(`i${id}`)?.remove()
  document.getElementById(id)?.remove()
}

/** Keep text labels while removing embedded HTML from model-authored SVG. */
export function normalizeMermaidSvg(svg: string) {
  const xmlSafe = svg.replace(/<(br|hr|img|input|meta|link)(\b[^>]*)>/gi, (tag, name, attributes) => tag.endsWith('/>') ? tag : `<${name}${attributes}/>`)
  const parsed = new DOMParser().parseFromString(xmlSafe, 'image/svg+xml')
  if (parsed.querySelector('parsererror')) throw new Error('Mermaid returned malformed SVG')
  for (const foreignObject of [...parsed.querySelectorAll('foreignObject')]) {
    const content = foreignObject.textContent?.replace(/\s+/g, ' ').trim()
    if (!content) { foreignObject.remove(); continue }
    const numeric = (key: string) => Number.parseFloat(foreignObject.getAttribute(key) ?? '0') || 0
    const label = parsed.createElementNS('http://www.w3.org/2000/svg', 'text')
    label.setAttribute('class', 'mermaid-safe-label')
    label.setAttribute('x', String(numeric('x') + numeric('width') / 2))
    label.setAttribute('y', String(numeric('y') + numeric('height') / 2))
    label.setAttribute('text-anchor', 'middle')
    label.setAttribute('dominant-baseline', 'middle')
    label.textContent = content
    foreignObject.replaceWith(label)
  }
  return new XMLSerializer().serializeToString(parsed.documentElement)
}

/** Shared by both UI runtimes. Mermaid retains all of its diagram grammars. */
export async function renderSafeMermaid(source: string, skin: SkinDefinition): Promise<SafeMermaidDiagram> {
  const id = nextDiagramId()
  try {
    const { svg, bindFunctions } = await renderDiagram(id, source, skin)
    return { id, svg: DOMPurify.sanitize(normalizeMermaidSvg(svg), { USE_PROFILES: { html: true, svg: true, svgFilters: true } }), bindFunctions }
  } finally {
    // Also clean obsolete successful renders and artifacts left by a failing engine.
    removeMermaidRenderArtifacts(id)
  }
}
