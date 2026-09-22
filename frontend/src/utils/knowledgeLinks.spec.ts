import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { knowledgeFileTarget, knowledgeLink } from './knowledgeLinks'

describe('knowledge file links', () => {
  it('renders file and citation links as local actions without opening another tab', () => {
    const wrapper = mount(MarkdownDocument, { props: { allowImages: false, resolveLink: knowledgeLink,
      content: '[代码](src/例子.java#L12-L15) [本地](file:///workspace/Example.java:20) [来源](knowledge:11111111-1111-1111-1111-111111111111#L1-L2) [网页](https://example.com) [危险](javascript:alert(1)) ![外部图](https://example.com/image.png)' } })
    const links = wrapper.findAll('a')
    expect(links).toHaveLength(4)
    expect(links[0]!.attributes('href')).toMatch(/^#knowledge-file-/)
    expect(links[1]!.attributes('href')).toMatch(/^#knowledge-file-/)
    expect(links[2]!.attributes('href')).toBe('#knowledge-citation-11111111-1111-1111-1111-111111111111#L1-L2')
    expect(links.slice(0, 3).every(link => !link.attributes('target'))).toBe(true)
    expect(links[3]!.attributes('target')).toBe('_blank')
    expect(wrapper.find('img').exists()).toBe(false)
    wrapper.unmount()
  })
  it('decodes Chinese paths and line formats, rejecting unsafe remote targets', () => {
    expect(knowledgeFileTarget('src/%E4%BE%8B%E5%AD%90.java#L12-L15')).toEqual({ path: 'src/例子.java', start: 12, end: 15 })
    expect(knowledgeFileTarget('file:///workspace/Example.java:20')).toEqual({ path: '/workspace/Example.java', start: 20, end: 20 })
    expect(knowledgeFileTarget('./README.md')).toEqual({ path: 'README.md', start: 1, end: 0 })
    expect(knowledgeLink('javascript:alert(1)')).toBeNull()
    expect(knowledgeLink('//external/file')).toBeNull()
    expect(() => knowledgeFileTarget('file://external/file')).toThrow()
    expect(() => knowledgeFileTarget('src/File.java#L20-L1')).toThrow()
  })
})
