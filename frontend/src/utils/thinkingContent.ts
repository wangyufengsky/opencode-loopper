export type ThinkingContentSegment = {
  type: 'content' | 'thinking'
  content: string
  complete: boolean
}

const THINK_MARKER = /<\s*(\/?)\s*think\s*>/gi

/**
 * Split provider-style <think> blocks before Markdown rendering.
 *
 * Some OpenCode/provider streams omit the opening marker but retain </think>.
 * In that case the content preceding the unmatched closing marker is still
 * treated as reasoning, which keeps the raw protocol tag out of the UI.
 */
export function splitThinkingContent(source: string): ThinkingContentSegment[] {
  const segments: ThinkingContentSegment[] = []
  let cursor = 0
  let thinking = false
  let match: RegExpExecArray | null

  const append = (type: ThinkingContentSegment['type'], content: string, complete: boolean) => {
    if (!content) return
    const previous = segments.at(-1)
    if (previous?.type === type && previous.complete === complete) {
      previous.content += content
      return
    }
    segments.push({ type, content, complete })
  }

  THINK_MARKER.lastIndex = 0
  while ((match = THINK_MARKER.exec(source)) !== null) {
    const closing = match[1] === '/'
    const chunk = source.slice(cursor, match.index)
    if (closing) {
      append('thinking', chunk, true)
      thinking = false
    } else {
      append(thinking ? 'thinking' : 'content', chunk, !thinking)
      thinking = true
    }
    cursor = match.index + match[0].length
  }

  const tail = source.slice(cursor)
  append(thinking ? 'thinking' : 'content', tail, !thinking)
  return segments.length > 0 ? segments : [{ type: 'content', content: '', complete: true }]
}
