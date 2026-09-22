import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { usePptStore } from '@/stores/pptStore'
import type { PptMessage } from '@/types/domain'
import { pptAgent, pptDocument } from './pptTestFixtures'
import PptChat from './PptChat.vue'

const message = (id: string, answer = '已完成调整'): PptMessage => ({
  id,
  documentId: 'doc',
  idempotencyKey: id,
  text: '做一份季度汇报',
  answer,
  state: 'COMPLETED',
  detail: '',
  scope: { kind: 'DOCUMENT' },
  expectedRevision: 3,
  version: 1,
  questions: [],
  createdAt: '2026-09-22T00:00:00Z',
  updatedAt: '2026-09-22T00:00:00Z',
})
function render() {
  return mount(PptChat, {
    props: { scope: { kind: 'DOCUMENT' }, scopeLabel: '整份演示文稿' },
    global: {
      stubs: {
        MarkdownDocument: {
          props: ['content'],
          template: '<div class="markdown-document">{{ content }}</div>',
        },
      },
    },
  })
}
beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
  const store = usePptStore()
  store.document = pptDocument()
  store.agent = pptAgent()
})

describe('PPT concise conversation', () => {
  it('requires an explicit confirmation card before starting design and keeps clarification replies separate', async () => {
    const store = usePptStore()
    const reply = vi.spyOn(store, 'reply').mockResolvedValue(true)
    const question = { id: 'brief', kind: 'CLARIFICATION' as const, prompt: '本次重点讲什么？', options: ['业务成果'], state: 'PENDING' as const, answer: null, version: 1 }
    store.agent = { ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'CLARIFYING', questions: [question] }
    const wrapper = render()
    expect(wrapper.text()).not.toContain('确认需求，开始设计')
    await wrapper.get('textarea').setValue('突出业务成果')
    await wrapper.get('.ppt-question').trigger('submit')
    expect(reply).toHaveBeenLastCalledWith(question, '突出业务成果', undefined)
    store.agent = { ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'AWAITING_CONFIRMATION', questions: [{ ...question, id: 'confirm', kind: 'REQUIREMENTS_CONFIRMATION', prompt: '## 汇报需求\n面向管理层，10 页，商务风格。' }] }
    await flushPromises()
    expect(wrapper.get('.ppt-requirements-confirmation .markdown-document').text()).toContain('10 页')
    const confirm = wrapper.findAll('button').find(button => button.text().includes('确认需求，开始设计'))!
    await wrapper.get('textarea').setValue('改成 8 页')
    expect(confirm.attributes('disabled')).toBeDefined()
    await wrapper.get('.ppt-question').trigger('submit')
    expect(reply).toHaveBeenLastCalledWith(store.agent.questions[0], '改成 8 页', false)
    await flushPromises()
    expect(confirm.attributes('disabled')).toBeUndefined()
    await confirm.trigger('click')
    expect(reply).toHaveBeenLastCalledWith(store.agent.questions[0], '确认以上需求，请开始设计', true)
    wrapper.unmount()
  })
  it('restores a pending requirements confirmation after remount without automatically accepting it', async () => {
    const store = usePptStore()
    const reply = vi.spyOn(store, 'reply').mockResolvedValue(true)
    store.agent = { ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'AWAITING_CONFIRMATION', questions: [{ id: 'confirm', kind: 'REQUIREMENTS_CONFIRMATION', prompt: '季度成果，面向管理层', options: [], state: 'PENDING', answer: null, version: 3 }] }
    let wrapper = render()
    await wrapper.get('textarea').setValue('增加风险说明')
    wrapper.unmount()
    wrapper = render()
    await flushPromises()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('增加风险说明')
    expect(wrapper.text()).toContain('确认需求，开始设计')
    expect(reply).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('shares knowledge waiting, thinking and tool disclosure behavior across streaming updates and stop', async () => {
    const store = usePptStore()
    store.messages = [{ ...message('stream', ''), state: 'RUNNING' }]
    const wrapper = render(); await flushPromises()
    expect(wrapper.get('.knowledge-waiting').text()).toContain('正在思考')
    store.messages = [{ ...message('stream', ''), state: 'RUNNING', thinking: '检查第一版\n\n正在检查页面', calls: [{ id: 'call', tool: 'ppt_check_layout', state: 'RUNNING', detail: '' }] }]
    await flushPromises()
    expect(wrapper.find('.knowledge-waiting').exists()).toBe(false)
    expect(wrapper.get('[aria-label="思考"] button').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('[aria-label="工具调用"] button').text()).toContain('检查页面布局')
    await wrapper.get('[aria-label="思考"] button').trigger('click')
    store.messages = [{ ...store.messages[0]!, thinking: '新的思考内容' }]; await flushPromises()
    expect(wrapper.get('[aria-label="思考"] button').attributes('aria-expanded')).toBe('true')
    store.messages = [{ ...store.messages[0]!, state: 'STOPPED' }]; await flushPromises()
    expect(wrapper.find('.knowledge-spinner').exists()).toBe(false)
    expect(wrapper.text()).toContain('新的思考内容')
    wrapper.unmount()
  })
  it('separates embedded thinking from answer and suppresses waiting while a question is pending', async () => {
    const store = usePptStore()
    store.messages = [{ ...message('stream', '<think>检查资料</think>完成设计'), state: 'RUNNING' }]
    const wrapper = render(); await flushPromises()
    expect(wrapper.get('[aria-label="思考"]').text()).toContain('检查资料')
    expect(wrapper.get('.ppt-agent-message').text()).toBe('完成设计')
    store.messages = [{ ...message('stream', ''), state: 'WAITING_INPUT' }]; await flushPromises()
    expect(wrapper.find('.knowledge-waiting').exists()).toBe(false)
    wrapper.unmount()
  })
  it('collapses long replies while keeping failures and pending questions visible', async () => {
    const store = usePptStore()
    const longReply =
      '这是一段详细制作过程。'.repeat(70) + '\n\n演示文稿已经完成，可继续提出修改意见。'
    store.messages = [
      message('one', longReply),
      { ...message('two', longReply), state: 'FAILED', detail: '图片无法读取，请更换图片后重试' },
    ]
    store.agent = {
      ...pptAgent(),
      state: 'WAITING_INPUT',
      questions: [
        {
          id: 'question',
          prompt: '请确认报告的目标受众',
          options: ['管理层'],
          state: 'PENDING',
          answer: null,
          version: 1,
        },
      ],
    }
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.ppt-long-reply')).toHaveLength(1)
    expect(wrapper.get('.ppt-long-reply').attributes('open')).toBeUndefined()
    expect(wrapper.get('.ppt-question').text()).toContain('请确认报告的目标受众')
    expect(wrapper.text()).toContain('图片无法读取，请更换图片后重试')
    expect(wrapper.find('.ppt-composer').exists()).toBe(false)
    expect(wrapper.findAll('.ppt-user-message')).toHaveLength(1)
  })
  it('follows new replies at the bottom but preserves an intentional history scroll', async () => {
    const store = usePptStore()
    store.messages = [message('one')]
    const wrapper = render()
    await flushPromises()
    const timeline = wrapper.get('.ppt-chat-timeline').element as HTMLElement
    Object.defineProperty(timeline, 'scrollHeight', { configurable: true, get: () => 1200 })
    Object.defineProperty(timeline, 'clientHeight', { configurable: true, get: () => 200 })
    timeline.scrollTop = 1000
    await wrapper.get('.ppt-chat-timeline').trigger('scroll')
    store.messages = [message('one', '已完成页面制作')]
    await flushPromises()
    expect(timeline.scrollTop).toBe(1200)
    timeline.scrollTop = 100
    await wrapper.get('.ppt-chat-timeline').trigger('scroll')
    store.messages = [message('one', '新的结果已经准备好')]
    await flushPromises()
    expect(timeline.scrollTop).toBe(100)
    expect(wrapper.get('.ppt-return-latest').text()).toContain('回到最新回复')
    await wrapper.get('.ppt-return-latest').trigger('click')
    expect(timeline.scrollTop).toBe(1200)
    expect(wrapper.find('.ppt-return-latest').exists()).toBe(false)
  })
})
