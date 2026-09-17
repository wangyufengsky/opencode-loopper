import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgeThinking from './KnowledgeThinking.vue'
import type { KnowledgeMessage } from '@/types/domain'
const message: KnowledgeMessage = { id: 'one', ordinal: 1, state: 'RUNNING', userText: '问题', answer: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }
describe('知识问答思考状态', () => {
  it('streams real content, keeps user collapse choice across updates and stops spinning on completion', async () => {
    const wrapper = mount(KnowledgeThinking, { attachTo: document.body, props: { message, thinking: '检查当前目录。' }, global: { stubs: { Icon: true } } })
    expect(wrapper.find('[aria-label="正在生成"]').exists()).toBe(true)
    expect(wrapper.get('.knowledge-thinking-content').isVisible()).toBe(true)
    await wrapper.get('button').trigger('click')
    await wrapper.setProps({ thinking: '检查当前目录。\n\n核对项目文档。' })
    expect(wrapper.get('.knowledge-thinking-content').isVisible()).toBe(false)
    await wrapper.setProps({ message: { ...message, state: 'COMPLETED' } })
    expect(wrapper.find('[aria-label="正在生成"]').exists()).toBe(false)
    await wrapper.get('button').trigger('click'); expect(wrapper.text()).toContain('核对项目文档')
    wrapper.unmount()
  })
  it('uses tool activity and an honest placeholder without provider thinking; unknown and stopped do not spin', async () => {
    const wrapper = mount(KnowledgeThinking, { attachTo: document.body, props: { message: { ...message, calls: [{ id: 'call', tool: 'read_knowledge_source', state: 'RUNNING', detail: '' }] }, thinking: '' }, global: { stubs: { Icon: true } } })
    expect(wrapper.text()).toContain('正在查阅资料'); expect(wrapper.text()).toContain('等待模型返回思考内容')
    await wrapper.setProps({ message: { ...message, state: 'UNKNOWN' } })
    expect(wrapper.find('[aria-label="正在生成"]').exists()).toBe(false); expect(wrapper.text()).toContain('正在核对连接')
    await wrapper.setProps({ message: { ...message, state: 'STOPPED' }, thinking: '已收到的内容' })
    expect(wrapper.find('[aria-label="正在生成"]').exists()).toBe(false)
    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false'); await wrapper.get('button').trigger('click')
    expect(wrapper.text()).toContain('已收到的内容'); wrapper.unmount()
  })
})
