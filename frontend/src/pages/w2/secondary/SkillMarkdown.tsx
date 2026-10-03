import { useMemo } from 'react'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { MermaidDiagram } from '@/react/diagrams/MermaidDiagram'
import type { SkinDefinition } from '@/themes/types'

const markdown = new MarkdownIt({ breaks: true, html: false, linkify: true, typographer: true })
markdown.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  const token = tokens[index]!
  if (!String(token.attrGet('href') ?? '').startsWith('#')) { token.attrSet('target', '_blank'); token.attrSet('rel', 'noopener noreferrer') }
  return self.renderToken(tokens, index, options)
}
/** Reuses the existing safe React Mermaid renderer; untrusted Markdown HTML is always sanitized. */
export function SkillMarkdown({ content, skin }: { content: string; skin: SkinDefinition }) {
  const parts = useMemo(() => {
    const tokens = markdown.parse(content, {})
    const pieces: { html?: string; mermaid?: string }[] = []
    let start = 0
    tokens.forEach((token, index) => {
      if (token.type !== 'fence' || token.info.trim() !== 'mermaid') return
      if (index > start) pieces.push({ html: DOMPurify.sanitize(markdown.renderer.render(tokens.slice(start, index), markdown.options, {}), { ADD_ATTR: ['target'], USE_PROFILES: { html: true } }) })
      pieces.push({ mermaid: token.content }); start = index + 1
    })
    if (start < tokens.length) pieces.push({ html: DOMPurify.sanitize(markdown.renderer.render(tokens.slice(start), markdown.options, {}), { ADD_ATTR: ['target'], USE_PROFILES: { html: true } }) })
    return pieces
  }, [content])
  return <div className="markdown-output" aria-label="Markdown 文档">{parts.map((part, index) => part.mermaid !== undefined ? <MermaidDiagram key={index} source={part.mermaid} skin={skin} /> : <div key={index} className="markdown-document" dangerouslySetInnerHTML={{ __html: part.html! }} />)}</div>
}
