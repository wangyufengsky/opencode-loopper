import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgeThinking from './KnowledgeThinking.vue'
import type { KnowledgeMessage } from '@/types/domain'
const message: KnowledgeMessage = { id: 'one', ordinal: 1, state: 'RUNNING', userText: '问题', answer: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }
describe('知识问答独立过程面板', () => {
  it.each([['PREPARED', '正在准备回答'], ['CREATING', '正在准备回答'], ['SENDING', '正在发送问题'], ['RUNNING', '正在思考']])('shows waiting feedback for an empty %s turn', (state, label) => {
    const wrapper = mount(KnowledgeThinking, { props: { message: { ...message, state }, thinking: '  ', answer: '\n' }, global: { stubs: { Icon: true } } })
    expect(wrapper.get('[role="status"]').text()).toBe(label)
    expect(wrapper.find('[aria-label="思考"]').exists()).toBe(false)
    expect(wrapper.get('.knowledge-waiting-dots').attributes('aria-hidden')).toBe('true')
    wrapper.unmount()
  })
  it('replaces waiting feedback with real output or a running tool and resumes after tools finish', async () => {
    const wrapper = mount(KnowledgeThinking, { props: { message, thinking: '', answer: '' }, global: { stubs: { Icon: true } } })
    const waiting = () => wrapper.find('[role="status"]').exists()
    expect(waiting()).toBe(true)
    await wrapper.setProps({ thinking: '实际返回的思考' }); expect(waiting()).toBe(false)
    await wrapper.setProps({ thinking: '', answer: '开始回答' }); expect(waiting()).toBe(false)
    const call = { id: 'call', tool: 'search_knowledge', state: 'RUNNING', detail: '' }
    await wrapper.setProps({ answer: '', message: { ...message, calls: [call] } })
    expect(waiting()).toBe(false); expect(wrapper.find('.knowledge-spinner').exists()).toBe(true)
    await wrapper.setProps({ message: { ...message, calls: [{ ...call, state: 'SUCCEEDED' }] } })
    expect(waiting()).toBe(true); expect(wrapper.find('[aria-label="工具调用"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it.each(['STOPPING', 'STOP_UNKNOWN', 'CREATE_UNKNOWN', 'SEND_UNKNOWN', 'COMPLETED', 'STOPPED', 'FAILED'])('does not animate an empty %s turn', state => {
    const wrapper = mount(KnowledgeThinking, { props: { message: { ...message, state }, thinking: '', answer: '' }, global: { stubs: { Icon: true } } })
    expect(wrapper.find('[role="status"]').exists()).toBe(false); wrapper.unmount()
  })
  it.each(['PENDING', 'PREPARED', 'SENDING', 'UNKNOWN'] as const)('hides waiting feedback during a %s question and resumes after its reply', async state => {
    const question = { id: 'question', state, questions: [], answers: [], version: 0, detail: '' }
    const wrapper = mount(KnowledgeThinking, { props: { message: { ...message, questions: [question] }, thinking: '', answer: '' }, global: { stubs: { Icon: true } } })
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    await wrapper.setProps({ message: { ...message, questions: [{ ...question, state: 'ANSWERED' }] } })
    expect(wrapper.get('[role="status"]').text()).toBe('正在思考'); wrapper.unmount()
  })
  it('defaults closed and keeps independent user choices while updating only latest previews', async () => {
    const wrapper = mount(KnowledgeThinking, { attachTo: document.body, props: { message: { ...message, calls: [{ id: 'one', tool: 'search_knowledge', state: 'SUCCEEDED', detail: '旧查询' }, { id: 'two', tool: 'read_knowledge_source', state: 'RUNNING', detail: 'Service.java' }] }, thinking: '旧思考。\n\n最新思考。', answer: '' }, global: { stubs: { Icon: true } } })
    const panels = wrapper.findAll('section'); expect(panels).toHaveLength(2)
    for (const panel of panels) expect(panel.get('button').attributes('aria-expanded')).toBe('false')
    expect(panels[0]!.get('button').text()).toContain('最新思考'); expect(panels[0]!.get('button').text()).not.toContain('旧思考')
    expect(panels[1]!.get('button').text()).toContain('Service.java'); expect(panels[1]!.get('button').text()).not.toContain('旧查询')
    await panels[0]!.get('button').trigger('click'); await wrapper.setProps({ thinking: '新增内容' })
    expect(panels[0]!.get('button').attributes('aria-expanded')).toBe('true'); expect(panels[1]!.get('button').attributes('aria-expanded')).toBe('false')
    await wrapper.setProps({ message: { ...message, state: 'STOPPED' } }); expect(wrapper.find('.knowledge-spinner').exists()).toBe(false); wrapper.unmount()
  })
  it('does not show an empty thinking placeholder when only tools have output', () => {
    const wrapper = mount(KnowledgeThinking, { props: { message: { ...message, calls: [{ id: 'one', tool: 'read_knowledge_source', state: 'RUNNING', detail: '' }] }, thinking: '', answer: '' }, global: { stubs: { Icon: true } } })
    expect(wrapper.find('[aria-label="思考"]').exists()).toBe(false); expect(wrapper.find('[aria-label="工具调用"]').exists()).toBe(true); expect(wrapper.text()).not.toContain('等待模型返回'); wrapper.unmount()
  })
})
