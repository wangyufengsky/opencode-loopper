export type MergeSide = 'source' | 'task'

export interface MergeConflictRegion {
  startOffset: number
  endOffset: number
  startLine: number
  endLine: number
  sourceContent: string
  taskContent: string
}

interface ContentLine {
  raw: string
  start: number
  end: number
}

function contentLines(content: string): ContentLine[] {
  if (!content) return []
  const lines: ContentLine[] = []
  let start = 0
  for (let index = 0; index < content.length; index += 1) {
    if (content[index] !== '\n') continue
    lines.push({ raw: content.slice(start, index + 1), start, end: index + 1 })
    start = index + 1
  }
  if (start < content.length) lines.push({ raw: content.slice(start), start, end: content.length })
  return lines
}

function marker(line: string, prefix: string) {
  const normalized = line.trimStart().replace(/\r?\n$/, '')
  return normalized === prefix || normalized.startsWith(`${prefix} `) || normalized.startsWith(`${prefix}\t`)
}

export function parseMergeConflicts(content: string): MergeConflictRegion[] {
  const lines = contentLines(content)
  const conflicts: MergeConflictRegion[] = []
  for (let index = 0; index < lines.length; index += 1) {
    const startLine = lines[index]
    if (!startLine || !marker(startLine.raw, '<<<<<<<')) continue
    let sourceEnd = -1
    let separator = -1
    let end = -1
    for (let cursor = index + 1; cursor < lines.length; cursor += 1) {
      const currentLine = lines[cursor]
      if (!currentLine) continue
      if (marker(currentLine.raw, '|||||||')) {
        if (sourceEnd < 0) sourceEnd = cursor
      } else if (currentLine.raw.trim() === '=======') {
        separator = cursor
        if (sourceEnd < 0) sourceEnd = cursor
      } else if (separator >= 0 && marker(currentLine.raw, '>>>>>>>')) {
        end = cursor
        break
      }
    }
    if (sourceEnd < 0 || separator < 0 || end < 0) continue
    const endLine = lines[end]
    if (!endLine) continue
    conflicts.push({
      startOffset: startLine.start,
      endOffset: endLine.end,
      startLine: index + 1,
      endLine: end + 1,
      sourceContent: lines.slice(index + 1, sourceEnd).map((line) => line.raw).join(''),
      taskContent: lines.slice(separator + 1, end).map((line) => line.raw).join(''),
    })
    index = end
  }
  return conflicts
}

export function resolveMergeConflict(content: string, conflictIndex: number, side: MergeSide) {
  const conflict = parseMergeConflicts(content)[conflictIndex]
  if (!conflict) return content
  const replacement = side === 'source' ? conflict.sourceContent : conflict.taskContent
  return `${content.slice(0, conflict.startOffset)}${replacement}${content.slice(conflict.endOffset)}`
}

function lineValues(content: string) {
  if (!content) return []
  return content.replace(/\r\n/g, '\n').split('\n')
}

export function changedLineNumbers(baseContent: string, targetContent: string) {
  const base = lineValues(baseContent)
  const target = lineValues(targetContent)
  if (!target.length) return []
  if (!base.length) return target.map((_, index) => index + 1)

  // Large generated files use a bounded prefix/suffix fallback to avoid quadratic memory.
  if (base.length * target.length > 1_000_000) {
    let prefix = 0
    while (prefix < base.length && prefix < target.length && base[prefix] === target[prefix]) prefix += 1
    let suffix = 0
    while (suffix < base.length - prefix && suffix < target.length - prefix
      && base[base.length - 1 - suffix] === target[target.length - 1 - suffix]) suffix += 1
    return Array.from({ length: Math.max(0, target.length - prefix - suffix) }, (_, index) => prefix + index + 1)
  }

  const width = target.length + 1
  const rows: Uint32Array[] = Array.from({ length: base.length + 1 }, () => new Uint32Array(width))
  for (let left = 1; left <= base.length; left += 1) {
    for (let right = 1; right <= target.length; right += 1) {
      rows[left]![right] = base[left - 1] === target[right - 1]
        ? (rows[left - 1]![right - 1] ?? 0) + 1
        : Math.max(rows[left - 1]![right] ?? 0, rows[left]![right - 1] ?? 0)
    }
  }
  const unchanged = new Set<number>()
  let left = base.length
  let right = target.length
  while (left > 0 && right > 0) {
    if (base[left - 1] === target[right - 1]) {
      unchanged.add(right)
      left -= 1
      right -= 1
    } else if ((rows[left - 1]![right] ?? 0) >= (rows[left]![right - 1] ?? 0)) left -= 1
    else right -= 1
  }
  return target.map((_, index) => index + 1).filter((line) => !unchanged.has(line))
}

export function countChangedGroups(lines: number[]) {
  const sorted = [...new Set(lines)].sort((left, right) => left - right)
  return sorted.reduce((count, line, index) => count + (index === 0 || line !== sorted[index - 1]! + 1 ? 1 : 0), 0)
}

export function languageForPath(path: string): 'java' | 'json' | 'plain' {
  if (/\.java$/i.test(path)) return 'java'
  if (/\.json$/i.test(path)) return 'json'
  return 'plain'
}
