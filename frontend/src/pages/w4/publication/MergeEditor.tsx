import { forwardRef, useImperativeHandle, useRef } from 'react'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { changedLineNumbers, languageForPath, parseMergeConflicts } from '@/utils/mergeView'
export interface MergeEditorHandle { scrollToLine(line: number): void }
/** Native controlled editing owns no document listeners, observer, RAF or external editor view. */
export const MergeEditor = forwardRef<MergeEditorHandle, { value: string; baseline: string; path: string; disabled?: boolean; activeIndex: number; onChange(value: string): void }>(function MergeEditor({ value, baseline, path, disabled, activeIndex, onChange }, ref) {
  const textarea = useRef<HTMLTextAreaElement>(null)
  const conflicts = parseMergeConflicts(value), active = conflicts[activeIndex]
  const highlighted = active ? Array.from({ length: active.endLine - active.startLine + 1 }, (_, index) => active.startLine + index) : changedLineNumbers(baseline, value)
  useImperativeHandle(ref, () => ({ scrollToLine(line) {
    const element = textarea.current; if (!element) return
    const lines = value.split('\n'), bounded = Math.max(1, Math.min(line, lines.length))
    const offset = lines.slice(0, bounded - 1).reduce((sum, text) => sum + text.length + 1, 0)
    element.focus(); element.setSelectionRange(offset, offset)
    const lineHeight = Number.parseFloat(getComputedStyle(element).lineHeight) || 20
    element.scrollTop = Math.max(0, (bounded - 1) * lineHeight - element.clientHeight / 2)
  } }), [value])
  return <div className="w4-merge-editor" data-language={languageForPath(path)}>
    <textarea ref={textarea} aria-label="合并结果编辑器" data-language={languageForPath(path)} spellCheck={false} value={value} disabled={disabled} onChange={event => onChange(event.target.value)} />
    <details><summary>查看合并结果语法与变化行</summary><ReadOnlyCode content={value} language={languageForPath(path)} label="合并结果语法预览" highlightLines={highlighted} /></details>
  </div>
})
