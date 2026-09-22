/** Local links are view actions, never URLs served by the SPA or unrestricted file access. */
export function knowledgeLink(href: string): string | null {
  if (/^knowledge:[a-f0-9-]{36}(?:#[LR]\d+-[LR]\d+)?$/i.test(href)) return `#knowledge-citation-${href.slice(10)}`
  if (/^https?:\/\//i.test(href) || /^mailto:/i.test(href)) return href
  if (href.startsWith('#')) return href.startsWith('#knowledge-') ? null : href
  if (/^(?!file:)[a-z][a-z\d+.-]*:/i.test(href) && !/^[a-z]:[\\/]/i.test(href)) return null
  if (href.startsWith('//')) return null
  return `#knowledge-file-${encodeURIComponent(href)}`
}
export function knowledgeFileTarget(href: string) {
  let value = decodeURIComponent(href)
  if (/^file:/i.test(value)) {
    const url = new URL(value)
    if (url.hostname && url.hostname !== 'localhost') throw new Error('不能打开远程文件，请从资料来源中查找')
    value = decodeURIComponent(url.pathname) + url.hash
  }
  const match = value.match(/(?:#L(\d+)(?:-L?(\d+))?|:(\d+)(?:-(\d+))?)$/)
  const start = Number(match?.[1] || match?.[3] || 1), end = Number(match?.[2] || match?.[4] || (match ? start : 0))
  let path = match ? value.slice(0, match.index) : value.split('#')[0]!
  if (path.startsWith('./')) path = path.slice(2)
  if (!path || path.includes('?') || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 1 || end > 0 && end < start) throw new Error('文件位置无效，请从资料来源中查找')
  return { path, start, end }
}
