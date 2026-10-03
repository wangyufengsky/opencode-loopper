import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { RichDocument } from './RichDocument'
import { ReadOnlyCode } from './ReadOnlyCode'

vi.mock('@/react/diagrams/MermaidDiagram', () => ({ MermaidDiagram: ({ source }: { source: string }) => <div data-canvas-runtime="react">{source}</div> }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
const wrapped = (content: string, extra = {}) => <FoundationProvider skin={skins[0]!}><RichDocument content={content} skin={skins[0]!} {...extra} /></FoundationProvider>
const intersectionEntry = (target: Element): IntersectionObserverEntry => ({ target, isIntersecting: true, time: 0, intersectionRatio: 1, rootBounds: null, boundingClientRect: new DOMRect(), intersectionRect: new DOMRect() })
describe('W3 shared document contracts', () => {
  it('sanitizes HTML and disables evidence images without removing their description', () => {
    const view = render(wrapped('<script>alert(1)</script>\n\n![原始附件](https://example.test/a.png)\n\n[危险](javascript:alert(1))', { allowImages: false }))
    expect(view.container.querySelector('script')).toBeNull(); expect(view.container.querySelector('img')).toBeNull()
    expect(view.container.textContent).toContain('原始附件'); expect(view.container.querySelector('a[href^="javascript:"]')).toBeNull()
  })
  it('preserves relative/file report resolution and rejects executable resolver results', () => {
    const onLink = vi.fn()
    const view = render(wrapped('[证据](file:///mock/report.md) [外部](https://example.test/) [危险](./bad)', { resolveLink: (href: string) => href.startsWith('file:') ? '#evidence' : href === './bad' ? 'javascript:alert(1)' : href, onLink }))
    const local = view.getByText('证据'); expect(local.getAttribute('href')).toBe('#evidence')
    fireEvent.click(local); expect(onLink).toHaveBeenCalledWith('#evidence', expect.anything())
    expect(view.getByText('危险').hasAttribute('href')).toBe(false)
    expect(view.getByText('外部').getAttribute('rel')).toBe('noopener noreferrer')
  })
  it('thinking is initially folded; evidence line highlights keep the original Markdown line numbering', () => {
    const view = render(wrapped('<think>分析</think>结果'))
    expect(view.container.querySelector('details')?.open).toBe(false); expect(view.container.textContent).toContain('结果')
    view.rerender(wrapped('# 原始文件\n\n证据段落\n\n其他段落', { highlightLines: [3] }))
    expect(view.container.querySelectorAll('.evidence-highlight')).toHaveLength(1)
    expect(view.container.querySelector('.evidence-highlight')?.textContent).toBe('证据段落')
  })
  it('keeps highlighted indented and fenced code evidence visible', () => {
    const view = render(wrapped('    原始代码\n\n```java\nclass Evidence {}\n```', { highlightLines: [1, 4] }))
    expect([...view.container.querySelectorAll('.evidence-highlight')].map(node => node.textContent)).toEqual(['原始代码\n', 'class Evidence {}\n'])
  })
  it('StrictMode owns and immediately disconnects figure/overflow observers; retired callbacks cannot mount a figure', () => {
    const intersection: { targets: Set<Element>; callback: IntersectionObserverCallback }[] = []
    const resize: Set<Element>[] = []
    class IO { targets = new Set<Element>(); constructor(readonly callback: IntersectionObserverCallback) { intersection.push(this) } observe(target: Element) { this.targets.add(target) } unobserve(target: Element) { this.targets.delete(target) } disconnect() { this.targets.clear() } }
    class RO { targets = new Set<Element>(); constructor() { resize.push(this.targets) } observe(target: Element) { this.targets.add(target) } disconnect() { this.targets.clear() } }
    vi.stubGlobal('IntersectionObserver', IO); vi.stubGlobal('ResizeObserver', RO)
    const view = render(<StrictMode>{wrapped('```mermaid\nflowchart LR\n A-->B\n```', { collapsible: true })}</StrictMode>)
    expect(view.container.querySelector('figure[data-w3-mermaid]'), view.container.innerHTML).not.toBeNull()
    expect(intersection.map(item => item.targets.size)).toContain(1)
    const active = intersection.find(item => item.targets.size)!
    const originalTarget = [...active.targets][0]!
    act(() => active.callback([intersectionEntry(originalTarget)], active as unknown as IntersectionObserver))
    expect(view.container.querySelector('[data-canvas-runtime="react"]')).not.toBeNull()
    view.unmount(); expect(intersection.every(item => item.targets.size === 0)).toBe(true); expect(resize.every(targets => targets.size === 0)).toBe(true)
    act(() => active.callback([intersectionEntry(originalTarget)], active as unknown as IntersectionObserver))
    expect(document.querySelector('[data-canvas-runtime="react"]')).toBeNull()
  })
  it('real React read-only source uses existing parser highlighting and original absolute line labels', () => {
    const view = render(<ReadOnlyCode content={'first\nsecond\nthird'} firstLineNumber={10} highlightLines={[11]} label="原始资料" />)
    const editor = view.getByLabelText('原始资料'); expect(editor.getAttribute('aria-readonly')).toBe('true'); expect(editor.getAttribute('contenteditable')).toBe('false')
    expect(view.container.querySelector('.evidence-highlight .w3-code-text')?.textContent).toBe('second')
    expect(view.container.querySelector('.w3-code-number')?.textContent).toBe('10')
    view.rerender(<ReadOnlyCode content={'class Finance { int n = 105; }'} language="java" />)
    expect(view.container.querySelector('.tok-keyword')?.textContent).toBe('class')
    view.unmount(); expect(view.container.querySelector('[data-code-renderer]')).toBeNull()
  })
  it('Mermaid is a direct React child inside nested lists and retains adjacent content and sanitized links', () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    const view = render(wrapped('- 前置条目\n\n  ```mermaid\n  flowchart LR\n   A-->B\n  ```\n\n- 后续条目 [证据](https://example.test)'))
    const list = view.container.querySelector('ul')!
    expect(list.querySelector('li figure [data-canvas-runtime="react"]')?.textContent).toContain('A-->B')
    expect(list.querySelectorAll('li')).toHaveLength(2)
    expect(view.getByText('证据').getAttribute('rel')).toBe('noopener noreferrer')
  })
})
