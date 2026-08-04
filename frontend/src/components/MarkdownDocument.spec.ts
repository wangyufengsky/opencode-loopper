import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MarkdownDocument from '@/components/MarkdownDocument.vue'

const mermaidMocks = vi.hoisted(() => ({
  initialize: vi.fn(),
  render: vi.fn(),
}))

vi.mock('mermaid', () => ({
  default: mermaidMocks,
}))

describe('MarkdownDocument', () => {
  beforeEach(() => {
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
})
