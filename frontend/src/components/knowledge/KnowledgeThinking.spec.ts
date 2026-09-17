import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgeThinking from './KnowledgeThinking.vue'
import type { KnowledgeMessage } from '@/types/domain'
const message: KnowledgeMessage = { id: 'one', ordinal: 1, state: 'RUNNING', userText: '问题', answer: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }
describe('知识问答独立过程面板', () => {
  it('defaults closed and keeps independent user choices while updating only latest previews', async () => {
    const wrapper = mount(KnowledgeThinking, { attachTo: document.body, props: { message: { ...message, calls: [{ id: 'one', tool: 'search_knowledge', state: 'SUCCEEDED', detail: '旧查询' }, { id: 'two', tool: 'read_knowledge_source', state: 'RUNNING', detail: 'Service.java' }] }, thinking: '旧思考。\n\n最新思考。' }, global: { stubs: { Icon: true } } })
    const panels = wrapper.findAll('section'); expect(panels).toHaveLength(2)
    for (const panel of panels) expect(panel.get('button').attributes('aria-expanded')).toBe('false')
    expect(panels[0]!.get('button').text()).toContain('最新思考'); expect(panels[0]!.get('button').text()).not.toContain('旧思考')
    expect(panels[1]!.get('button').text()).toContain('Service.java'); expect(panels[1]!.get('button').text()).not.toContain('旧查询')
    await panels[0]!.get('button').trigger('click'); await wrapper.setProps({ thinking: '新增内容' })
    expect(panels[0]!.get('button').attributes('aria-expanded')).toBe('true'); expect(panels[1]!.get('button').attributes('aria-expanded')).toBe('false')
    await wrapper.setProps({ message: { ...message, state: 'STOPPED' } }); expect(wrapper.find('.knowledge-spinner').exists()).toBe(false); wrapper.unmount()
  })
  it('does not show an empty thinking placeholder when only tools have output', () => {
    const wrapper = mount(KnowledgeThinking, { props: { message: { ...message, calls: [{ id: 'one', tool: 'read_knowledge_source', state: 'RUNNING', detail: '' }] }, thinking: '' }, global: { stubs: { Icon: true } } })
    expect(wrapper.find('[aria-label="思考"]').exists()).toBe(false); expect(wrapper.find('[aria-label="工具调用"]').exists()).toBe(true); expect(wrapper.text()).not.toContain('等待模型返回'); wrapper.unmount()
  })
})
