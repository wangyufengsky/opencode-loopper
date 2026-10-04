import { enableAutoUnmount, flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RichDocument as MarkdownDocument } from '@/pages/w3/shared/RichDocument'
import { applySkin } from '@/themes/state'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'

enableAutoUnmount(afterEach)

const mermaidMocks = vi.hoisted(() => ({
  initialize: vi.fn(),
  render: vi.fn(),
}))

vi.mock('mermaid', () => ({
  default: mermaidMocks,
}))

describe('MarkdownDocument', () => {
  afterEach(() => { vi.unstubAllGlobals(); localStorage.removeItem(CANVAS_RUNTIME_STORAGE); applySkin('tech-blue', false) })
  beforeEach(() => {
    localStorage.removeItem(CANVAS_RUNTIME_STORAGE)
    vi.stubGlobal('IntersectionObserver', undefined)
    mermaidMocks.render.mockReset()
    mermaidMocks.render.mockResolvedValue({ svg: '<svg role="img"><foreignObject width="120" height="30"><div><p>Rendered flow<br> safely</p><img src="x" onerror="alert(1)"></div></foreignObject></svg>' })
  })

  it('renders structured Markdown while treating raw HTML as text', () => {
    const wrapper = mount(MarkdownDocument, {
      props: { content: '# 方案\n\n- 第一步\n- 第二步\n\n[文档](https://example.com)\n\n<script>alert(1)</script>' },
    })

    expect(wrapper.get('h1').text()).toBe('方案')
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.get('a').attributes()).toMatchObject({
      href: 'https://example.com',
      target: '_blank',
      rel: 'noopener noreferrer',
    })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
  })

  it('renders complete think blocks as a separate thinking card without leaking protocol tags', () => {
    const wrapper = mount(MarkdownDocument, {
      props: { thinkingPresentation: 'expanded', content: '<think>正在检查项目结构与测试约定。</think>\n\n## 设计方案\n\n补充单元测试。' },
    })

    const card = wrapper.get('[aria-label="思考过程"]')
    expect(card.text()).toContain('思考过程')
    expect(card.text()).toContain('已完成')
    expect(card.text()).toContain('正在检查项目结构与测试约定。')
    expect(wrapper.get('.markdown-body-segment h2').text()).toBe('设计方案')
    expect(wrapper.text()).not.toContain('<think>')
    expect(wrapper.text()).not.toContain('</think>')
  })

  it('recognizes an unmatched closing think tag returned by a provider', () => {
    const wrapper = mount(MarkdownDocument, {
      props: { thinkingPresentation: 'expanded', content: '找到了目标类，现在需要读取源码。\n</think>\n\n开始生成设计文档。' },
    })

    expect(wrapper.get('[aria-label="思考过程"]').text()).toContain('找到了目标类')
    expect(wrapper.get('.markdown-body-segment').text()).toContain('开始生成设计文档')
    expect(wrapper.text()).not.toContain('</think>')
  })

  it('marks a streaming unclosed think block active and lets the user collapse it', async () => {
    const wrapper = mount(MarkdownDocument, {
      props: { thinkingPresentation: 'expanded', content: '<think>正在持续分析依赖关系。' },
    })

    const card = wrapper.get('[aria-label="思考过程"]')
    expect(card.attributes('aria-busy')).toBe('true')
    expect(card.text()).toContain('思考中')
    await card.get('.markdown-thinking-toggle').trigger('click')
    expect(card.get('.markdown-thinking-toggle').attributes('aria-expanded')).toBe('false')
    expect(card.find('.markdown-thinking-content').isVisible()).toBe(false)
  })

  it('turns fenced Mermaid source into a rendered diagram', async () => {
    const source = 'flowchart LR\n  A[需求] --> B[实现]'
    const wrapper = mount(MarkdownDocument, {
      props: { content: `## 流程\n\n\`\`\`mermaid\n${source}\n\`\`\`` },
    })
    await flushPromises()

    expect(mermaidMocks.render).toHaveBeenCalledWith(expect.stringMatching(/^loopper-mermaid-/), `${source}\n`)
    await waitFor(() => expect(wrapper.find('figure[aria-label="Mermaid 图示"] svg').exists()).toBe(true))
    expect(wrapper.find('[data-canvas-runtime="react"] .react-mermaid-svg svg').exists()).toBe(true)
    expect(wrapper.get('figure[aria-label="Mermaid 图示"]').text()).toContain('Rendered flow')
    expect(wrapper.find('foreignObject').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.find('parsererror').exists()).toBe(false)
    expect(wrapper.find('code.language-mermaid').exists()).toBe(false)
  })

  it('removes Mermaid error renderer artifacts when parsing fails', async () => {
    mermaidMocks.render.mockImplementation(async (id: string) => {
      const leakedError = document.createElement('div')
      leakedError.id = `d${id}`
      leakedError.innerHTML = `<svg id="${id}"><text>Syntax error in text</text></svg>`
      document.body.append(leakedError)
      throw new Error('Parse error')
    })
    const wrapper = mount(MarkdownDocument, {
      props: { content: '```mermaid\nflowchart LR\nA[@ChainConfig] --> B\n```' },
    })
    await flushPromises()

    await waitFor(() => expect(wrapper.find('.react-mermaid-diagram.is-error').exists()).toBe(true))
    expect(wrapper.get('.react-mermaid-diagram.is-error').text()).toBe('流程图语法无法渲染，请检查 Mermaid 文本。')
    expect(document.body.textContent).not.toContain('Syntax error in text')
    expect(document.querySelector('[id^="dloopper-mermaid-"]')).toBeNull()
    wrapper.unmount()
  })

  it('defers Mermaid loading until the diagram approaches the viewport', async () => {
    let notify: ((entries: Array<{ isIntersecting: boolean; target: Element }>) => void) | undefined
    class FakeIntersectionObserver {
      constructor(callback: typeof notify) { notify = callback }
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    const wrapper = mount(MarkdownDocument, {
      props: { content: '```mermaid\nflowchart LR\nA --> B\n```' },
    })
    await flushPromises()

    const placeholder = wrapper.get('figure').element
    expect(mermaidMocks.render).not.toHaveBeenCalled()
    notify?.([{ isIntersecting: true, target: placeholder }])
    await flushPromises()
    expect(mermaidMocks.render).toHaveBeenCalled()
    await waitFor(() => expect(wrapper.find('.react-mermaid-diagram.is-ready').exists()).toBe(true))
    expect(wrapper.find('.react-mermaid-diagram.is-ready').exists()).toBe(true)
  })

  it('collapses overflowing output to three lines until the user expands it', async () => {
    const scrollHeight = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(120)
    const clientHeight = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(67)
    const wrapper = mount(MarkdownDocument, {
      props: { content: '第一行\n\n第二行\n\n第三行\n\n第四行', collapsible: true },
    })
    await flushPromises()

    expect(wrapper.get('.markdown-document').attributes('style')).toContain('max-height: 72px')
    expect(wrapper.get('.markdown-document').attributes('style')).toContain('overflow: hidden')
    expect(wrapper.get('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').attributes('aria-label')).toBe('展开：完整输出')
    expect(wrapper.get('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').attributes('aria-expanded')).toBe('false')

    await wrapper.get('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').trigger('click')

    expect(wrapper.get('.markdown-document').attributes('style')).not.toContain('max-height: 72px')
    expect(wrapper.get('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').text()).toContain('收起')
    expect(wrapper.get('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').attributes('aria-expanded')).toBe('true')
    scrollHeight.mockRestore()
    clientHeight.mockRestore()
  })

  it('does not show an expand control when output fits within three lines', async () => {
    const scrollHeight = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(60)
    const clientHeight = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(60)
    const wrapper = mount(MarkdownDocument, {
      props: { content: '简短输出', collapsible: true },
    })
    await flushPromises()

    expect(wrapper.find('button[data-semantic="ui.expand"],button[data-semantic="ui.collapse"]').exists()).toBe(false)
    scrollHeight.mockRestore()
    clientHeight.mockRestore()
  })
  it('切换皮肤重新渲染已有流程图并保留用户折叠选择', async () => {
    const wrapper = mount(MarkdownDocument, { props: { thinkingPresentation: 'expanded', content: '<think>分析内容</think>\n\n```mermaid\nflowchart LR\nA --> B\n```' } })
    await flushPromises()
    await wrapper.get('.markdown-thinking-toggle').trigger('click')
    const before = mermaidMocks.render.mock.calls.length
    applySkin('github-white', false)
    await flushPromises()
    expect(mermaidMocks.render.mock.calls.length).toBe(before + 1)
    expect(mermaidMocks.initialize).toHaveBeenLastCalledWith(expect.objectContaining({ theme: 'base', securityLevel: 'strict', themeVariables: expect.objectContaining({ primaryTextColor: '#1f2328' }) }))
    expect(wrapper.get('.markdown-thinking-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('figure svg').exists()).toBe(true)
  })

  it('多个实例的主题渲染串行执行，迟到的旧主题结果不覆盖新主题', async () => {
    let release: ((value: { svg: string }) => void) | undefined
    mermaidMocks.render.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const wrapper = mount(MarkdownDocument, { props: { content: '```mermaid\nflowchart LR\nA --> B\n```' } })
    await flushPromises()
    applySkin('github-white', false)
    const second = mount(MarkdownDocument, { props: { content: '```mermaid\nflowchart LR\nC --> D\n```' } })
    await flushPromises()
    expect(mermaidMocks.render).toHaveBeenCalledTimes(1)
    release!({ svg: '<svg><text>obsolete dark diagram</text></svg>' })
    await flushPromises()
    await waitFor(() => {
      expect(wrapper.find('figure svg').exists()).toBe(true)
      expect(second.find('figure svg').exists()).toBe(true)
    })
    expect(wrapper.text()).not.toContain('obsolete dark diagram')
    expect(wrapper.find('figure svg').exists()).toBe(true)
    expect(second.find('figure svg').exists()).toBe(true)
    const ids = mermaidMocks.render.mock.calls.map(call => call[0])
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('内容替换会卸载旧 React 图，迟到的旧 SVG 不进入新文档', async () => {
    let release: ((value: { svg: string }) => void) | undefined
    mermaidMocks.render.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    mermaidMocks.render.mockResolvedValue({ svg: '<svg><text>当前文档图</text></svg>' })
    const wrapper = mount(MarkdownDocument, { props: { content: '```mermaid\nflowchart LR\nA --> B\n```' } })
    await flushPromises()
    await waitFor(() => expect(mermaidMocks.render).toHaveBeenCalledTimes(1))
    const oldFrame = wrapper.get('figure').element
    await wrapper.setProps({ content: '```mermaid\nsequenceDiagram\n用户->>程序: 新文档\n```' })
    release!({ svg: '<svg><text>旧文档图</text></svg>' })
    await waitFor(() => expect(wrapper.text()).toContain('当前文档图'))
    expect(wrapper.text()).not.toContain('旧文档图')
    expect(oldFrame.textContent).not.toContain('旧文档图')
    expect(mermaidMocks.render).toHaveBeenLastCalledWith(expect.any(String), expect.stringContaining('sequenceDiagram'))
  })

  it('证据高亮变化重建 Markdown 时会重建真实 React 图并清理旧根', async () => {
    const wrapper = mount(MarkdownDocument, { props: { content: '# 验收证据\n\n```mermaid\nflowchart LR\nA --> B\n```' } })
    await waitFor(() => expect(wrapper.find('.react-mermaid-svg svg').exists()).toBe(true))
    const previous = wrapper.get('figure').element
    await wrapper.setProps({ highlightLines: [1] })
    await waitFor(() => expect(wrapper.find('.react-mermaid-svg svg').exists()).toBe(true))
    expect(previous.querySelector('.react-mermaid-diagram')?.getAttribute('data-canvas-runtime')).toBe('react')
    expect(wrapper.get('h1').classes()).toContain('evidence-highlight')
  })

  it('已开始图在等待新主题可见期间也拒绝旧主题迟到结果', async () => {
    let notify: ((entries: Array<{ isIntersecting: boolean; target: Element }>) => void) | undefined
    const disconnect = vi.fn()
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: typeof notify) { notify = callback }
      observe = vi.fn(); unobserve = vi.fn(); disconnect = disconnect
    })
    let release: ((value: { svg: string }) => void) | undefined
    mermaidMocks.render.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const wrapper = mount(MarkdownDocument, { props: { content: '```mermaid\nflowchart LR\nA --> B\n```' } })
    await flushPromises()
    const frame = wrapper.get('figure').element
    notify?.([{ isIntersecting: true, target: frame }])
    await waitFor(() => expect(mermaidMocks.render).toHaveBeenCalledTimes(1))
    applySkin('github-white', false)
    await flushPromises()
    release!({ svg: '<svg><text>obsolete deferred theme</text></svg>' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('obsolete deferred theme')
    notify?.([{ isIntersecting: true, target: frame }])
    await waitFor(() => expect(wrapper.find('figure svg').exists()).toBe(true))
    expect(mermaidMocks.render).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    expect(disconnect).toHaveBeenCalled()
    expect(frame.isConnected).toBe(false)
  })

  it('新实例可回退 Vue，仍共用安全图服务且不替换已挂载的 React 图', async () => {
    const content = '```mermaid\nflowchart LR\nA --> B\n```'
    const current = mount(MarkdownDocument, { props: { content } })
    await waitFor(() => expect(current.find('.react-mermaid-svg svg').exists()).toBe(true))
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ documents: 'vue' }))
    const fallback = mount(MarkdownDocument, { props: { content } })
    await waitFor(() => expect(fallback.find('[data-canvas-runtime="react"] svg').exists()).toBe(true))
    expect(fallback.find('.react-mermaid-diagram').exists()).toBe(true)
    expect(fallback.find('foreignObject, [onerror]').exists()).toBe(false)
    expect(current.find('.react-mermaid-svg svg').exists()).toBe(true)
  })

})
