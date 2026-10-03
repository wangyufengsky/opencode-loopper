import { useMemo } from 'react'
import { javaLanguage } from '@codemirror/lang-java'
import { jsonLanguage } from '@codemirror/lang-json'
import { classHighlighter, highlightTree } from '@lezer/highlight'
import './documents.css'

export interface ReadOnlyCodeProps { content: string; language?: 'java' | 'json' | 'plain'; highlightLines?: number[]; label?: string; firstLineNumber?: number; lineWrapping?: boolean }
/** A read-only projection uses the existing pure parsers, without an editor's DOM resources. */
export function ReadOnlyCode({ content, language = 'plain', highlightLines = [], label = '只读代码', firstLineNumber = 1, lineWrapping = true }: ReadOnlyCodeProps) {
  const tokens = useMemo(() => {
    const ranges: { from: number; to: number; classes: string }[] = []
    const parser = language === 'java' ? javaLanguage.parser : language === 'json' ? jsonLanguage.parser : undefined
    if (parser) highlightTree(parser.parse(content), classHighlighter, (from, to, classes) => ranges.push({ from, to, classes }))
    let offset = 0, rangeIndex = 0
    return content.split('\n').map(text => {
      const start = offset, end = start + text.length, parts: { text: string; classes?: string }[] = []
      let cursor = start
      while (rangeIndex < ranges.length && ranges[rangeIndex]!.to <= start) rangeIndex++
      for (let index = rangeIndex; index < ranges.length && ranges[index]!.from < end; index++) {
        const range = ranges[index]!
        const from = Math.max(start, range.from), to = Math.min(end, range.to)
        if (from > cursor) parts.push({ text: content.slice(cursor, from) })
        parts.push({ text: content.slice(from, to), classes: range.classes }); cursor = to
      }
      if (cursor < end) parts.push({ text: content.slice(cursor, end) })
      offset = end + 1
      return parts
    })
  }, [content, language])
  const marked = new Set(highlightLines)
  return <div className="w3-readonly-code" data-code-renderer="react-lezer"><div className={`w3-code-view ${lineWrapping ? 'wrap' : ''}`} role="textbox" aria-label={label} aria-readonly="true" contentEditable={false} tabIndex={0}>
    {tokens.map((parts, index) => <div key={index} className={`w3-code-line ${marked.has(firstLineNumber + index) ? 'evidence-highlight' : ''}`} data-line={firstLineNumber + index}>
      <span className="w3-code-number" aria-hidden="true">{firstLineNumber + index}</span><code className="w3-code-text">{parts.map((part, i) => <span key={i} className={part.classes}>{part.text}</span>)}</code>
    </div>)}
  </div></div>
}
