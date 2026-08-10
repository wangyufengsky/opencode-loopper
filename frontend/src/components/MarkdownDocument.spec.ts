import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MarkdownDocument from '@/components/MarkdownDocument.vue'

const mermaidMocks = vi.hoisted(() => ({
  initialize: vi.fn(),
  render: vi.fn(),
}))

vi.mock('mermaid', () => ({
  default: mermaidMocks,
}))

describe('MarkdownDocument', () => {
  afterEach(() => vi.unstubAllGlobals())
  beforeEach(() => {
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
      props: { content: '<think>正在检查项目结构与测试约定。</think>\n\n## 设计方案\n\n补充单元测试。' },
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
      props: { content: '找到了目标类，现在需要读取源码。\n</think>\n\n开始生成设计文档。' },
    })

    expect(wrapper.get('[aria-label="思考过程"]').text()).toContain('找到了目标类')
    expect(wrapper.get('.markdown-body-segment').text()).toContain('开始生成设计文档')
    expect(wrapper.text()).not.toContain('</think>')
  })

  it('marks a streaming unclosed think block active and lets the user collapse it', async () => {
    const wrapper = mount(MarkdownDocument, {
      props: { content: '<think>正在持续分析依赖关系。' },
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

    expect(mermaidMocks.render).toHaveBeenCalledWith(expect.stringMatching(/^loopper-mermaid-/), source)
    expect(wrapper.get('figure[aria-label="Mermaid 流程图"]').text()).toContain('Rendered flow')
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

    expect(wrapper.get('.markdown-mermaid-error').text()).toBe('流程图语法无法渲染，请检查 Mermaid 文本。')
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

    const placeholder = wrapper.get('.markdown-mermaid-pending').element
    expect(mermaidMocks.render).not.toHaveBeenCalled()
    notify?.([{ isIntersecting: true, target: placeholder }])
    await flushPromises()
    expect(mermaidMocks.render).toHaveBeenCalled()
    expect(wrapper.find('.markdown-mermaid-pending').exists()).toBe(false)
  })

  it('collapses overflowing output to three lines until the user expands it', async () => {
    const scrollHeight = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(120)
    const clientHeight = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(67)
    const wrapper = mount(MarkdownDocument, {
      props: { content: '第一行\n\n第二行\n\n第三行\n\n第四行', collapsible: true },
    })
    await flushPromises()

    expect(wrapper.get('.markdown-document').classes()).toContain('is-collapsed')
    expect(wrapper.get('.markdown-document').attributes('style')).toContain('--collapsed-lines: 3')
    expect(wrapper.get('.markdown-expand-button').text()).toContain('展开完整输出')
    expect(wrapper.get('.markdown-expand-button').attributes('aria-expanded')).toBe('false')

    await wrapper.get('.markdown-expand-button').trigger('click')

    expect(wrapper.get('.markdown-document').classes()).not.toContain('is-collapsed')
    expect(wrapper.get('.markdown-expand-button').text()).toContain('收起输出')
    expect(wrapper.get('.markdown-expand-button').attributes('aria-expanded')).toBe('true')
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

    expect(wrapper.find('.markdown-expand-button').exists()).toBe(false)
    scrollHeight.mockRestore()
    clientHeight.mockRestore()
  })
})
