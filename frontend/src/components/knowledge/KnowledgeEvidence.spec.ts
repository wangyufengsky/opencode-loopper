import { mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { describe, expect, it } from 'vitest'
import { KnowledgeEvidence } from '@/pages/w3/knowledge/Evidence'

describe('保存的资料概览', () => {
  it('shows captured metadata and incomplete coverage without a text body', () => {
    const body = { kind: 'DIRECTORY', name: '项目代码', sourceId: 'code', path: '', sha256: 'abc123', items: [{ path: 'src/App.java', directory: false }], incomplete: true }
    const wrapper = mount(KnowledgeEvidence, { props: { body }, global: { stubs: { CodeMergeEditor: { props: ['modelValue'], template: '<pre>{{ modelValue }}</pre>' }, MarkdownDocument: true } } })
    expect(wrapper.text()).toContain('采集时保存')
    expect(wrapper.text()).toContain('src/App.java')
    expect(wrapper.text()).toContain('未覆盖全部资料')
    wrapper.unmount()
  })
})
