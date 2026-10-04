import { createElement, useEffect, useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { UiActionButton } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { MermaidDiagram } from '@/react/diagrams/MermaidDiagram'
import { splitThinkingContent } from '@/utils/thinkingContent'
import type { SkinDefinition } from '@/themes/types'
import './documents.css'

export interface RichDocumentProps {
  content: string; skin: SkinDefinition; allowImages?: boolean; highlightLines?: number[]
  resolveLink?: (href: string) => string | null; collapsible?: boolean; collapsedLines?: number
  onLink?: (href: string, event: MouseEvent<HTMLDivElement>) => void
  /** Reports fold reasoning; live Designer Markdown preserves its original expanded presentation. */
  thinkingPresentation?: 'folded' | 'expanded'
}
/** This figure is a direct child of the existing React root, never a portal root. */
function LazyFigure({ source, skin, marker }: { source: string; skin: SkinDefinition; marker: string }) {
  const host = useRef<HTMLElement>(null), [ready, setReady] = useState(false)
  useEffect(() => {
    const target = host.current
    if (!target) return
    let active = true
    if (typeof IntersectionObserver === 'undefined') { setReady(true); return () => { active = false } }
    const observer = new IntersectionObserver(entries => {
      if (active && entries.some(entry => entry.target === target && entry.isIntersecting)) { observer.unobserve(target); setReady(true) }
    }, { rootMargin: '240px 0px' })
    observer.observe(target)
    return () => { active = false; observer.disconnect() }
  }, [source])
  return <figure ref={host} data-w3-mermaid={marker} aria-label="Mermaid 图示">{ready ? <MermaidDiagram source={source} skin={skin} /> : '图示将在滚动到此处时加载…'}</figure>
}
function ThinkingCard({ complete, children, presentation }: { complete: boolean; children: ReactNode; presentation: 'folded' | 'expanded' }) {
  const [expanded, setExpanded] = useState(presentation === 'expanded')
  if (presentation === 'folded') return <details className={`markdown-thinking-card${complete ? '' : ' is-active'}`} aria-label="思考过程" aria-busy={!complete} open={expanded} onToggle={event => setExpanded(event.currentTarget.open)}>
    <summary><SemanticIcon semanticKey={expanded ? 'ui.collapse' : 'ui.expand'} /><strong>思考过程</strong><span>{complete ? '已完成' : '思考中'}</span></summary>
    <div className="markdown-thinking-content">{children}</div>
  </details>
  return <section className={`markdown-thinking-card${complete ? '' : ' is-active'}`} aria-label="思考过程" aria-busy={!complete}>
    <header className="markdown-thinking-header"><strong>思考过程</strong><span>{complete ? '已完成' : '思考中'}</span>
      <UiActionButton className="markdown-thinking-toggle" actionKey={expanded ? 'ui.collapse' : 'ui.expand'} target="思考过程" expanded={expanded} onAction={() => setExpanded(value => !value)} />
    </header><div className="markdown-thinking-content" hidden={!expanded}>{children}</div>
  </section>
}
/** Convert only the already-sanitized Markdown DOM, preserving nested lists and table structure. */
function reactDocument(html: string, sources: string[], skin: SkinDefinition): ReactNode[] {
  const template = document.createElement('template'); template.innerHTML = html
  function convert(node: Node, key: string): ReactNode {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent
    if (!(node instanceof HTMLElement)) return null
    const marker = node.dataset.w3Mermaid
    if (node.tagName === 'FIGURE' && marker !== undefined && /^\d+$/.test(marker)) return <LazyFigure key={key} marker={marker} source={sources[Number(marker)] ?? ''} skin={skin} />
    const props: Record<string, unknown> = { key }
    const names: Record<string, string> = { class: 'className', for: 'htmlFor', colspan: 'colSpan', rowspan: 'rowSpan', tabindex: 'tabIndex' }
    for (const attribute of node.attributes) {
      if (/^on/i.test(attribute.name) || ['style', 'srcdoc', 'dangerouslysetinnerhtml'].includes(attribute.name)) continue
      props[names[attribute.name] ?? attribute.name] = attribute.value
    }
    const children = [...node.childNodes].map((child, index) => convert(child, `${key}.${index}`))
    return createElement(node.tagName.toLowerCase(), props, ...children)
  }
  return [...template.content.childNodes].map((node, index) => convert(node, String(index)))
}
/** One sanitized React renderer for reports and evidence, with independently owned figures. */
export function RichDocument({ content, skin, allowImages = true, highlightLines, resolveLink, collapsible = false, collapsedLines = 3, onLink, thinkingPresentation = 'folded' }: RichDocumentProps) {
  const [host, setHost] = useState<HTMLDivElement | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [overflow, setOverflow] = useState(false)
  const segments = useMemo(() => highlightLines?.length ? [{ type: 'content' as const, content }] : splitThinkingContent(content), [content, highlightLines])
  const { html, sources } = useMemo(() => {
    const sources: string[] = []
    const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: true })
    const validate = markdown.validateLink
    markdown.validateLink = href => validate(href) || !!resolveLink && /^file:/i.test(href)
    if (!allowImages) markdown.renderer.rules.image = (tokens, i) => markdown.utils.escapeHtml(tokens[i]!.content)
    markdown.renderer.rules.link_open = (tokens, i, options, _env, renderer) => {
      const token = tokens[i]!, original = String(token.attrGet('href') ?? '')
      const href = resolveLink ? resolveLink(original) : original
      // Resolvers cannot turn a safe source into executable content.
      if (!href || !validate(href)) token.attrs = token.attrs?.filter(([key]) => key !== 'href') ?? null
      else { token.attrSet('href', href); if (!href.startsWith('#')) { token.attrSet('target', '_blank'); token.attrSet('rel', 'noopener noreferrer') } }
      return renderer.renderToken(tokens, i, options)
    }
    const fence = markdown.renderer.rules.fence!
    markdown.renderer.rules.fence = (tokens, i, options, env, renderer) => {
      if (tokens[i]!.info.trim() !== 'mermaid') return fence(tokens, i, options, env, renderer)
      const index = sources.push(tokens[i]!.content) - 1
      return `<figure data-w3-mermaid="${index}" aria-label="Mermaid 图示"></figure>`
    }
    if (highlightLines?.length) markdown.core.ruler.after('block', 'w3-evidence-lines', state => {
      const lines = new Set(highlightLines)
      for (const token of state.tokens) if (token.map && ['paragraph_open', 'heading_open', 'tr_open', 'fence', 'code_block'].includes(token.type)) {
        if (Array.from({ length: token.map[1] - token.map[0] }, (_, i) => token.map![0] + i + 1).some(line => lines.has(line))) token.attrJoin('class', 'evidence-highlight')
      }
    })
    return { html: segments.map(segment => DOMPurify.sanitize(markdown.render(segment.content), { ADD_ATTR: ['target'], USE_PROFILES: { html: true } })), sources }
  }, [segments, allowImages, resolveLink, highlightLines])
  useEffect(() => {
    if (!host || !collapsible) return
    let active = true
    const measure = () => { if (active) setOverflow(host.scrollHeight > collapsedLines * 24 + 1) }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure); observer.observe(host)
    return () => { active = false; observer.disconnect() }
  }, [host, html, collapsible, collapsedLines])
  return <div className="w3-rich-document">
    <div ref={setHost} className="w3-document-body markdown-document" style={collapsible && !expanded ? { maxHeight: collapsedLines * 24, overflow: 'hidden' } : undefined}
      onClick={event => { const target = event.target instanceof Element ? event.target.closest('a[href]') : null; if (target) onLink?.(target.getAttribute('href')!, event) }}>
      {segments.map((segment, i) => segment.type === 'thinking' ? <ThinkingCard key={i} complete={segment.complete} presentation={thinkingPresentation}>{reactDocument(html[i]!, sources, skin)}</ThinkingCard>
        : <div key={i} className="markdown-body-segment">{reactDocument(html[i]!, sources, skin)}</div>)}
    </div>
    {collapsible && (overflow || expanded) && <UiActionButton actionKey={expanded ? 'ui.collapse' : 'ui.expand'} target="完整输出" expanded={expanded} onAction={() => setExpanded(value => !value)} />}
  </div>
}
