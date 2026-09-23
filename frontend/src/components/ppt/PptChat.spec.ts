import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { usePptStore } from '@/stores/pptStore'
import type { PptMessage } from '@/types/domain'
import { pptAgent, pptDocument, pptGeneration } from './pptTestFixtures'
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
function render(ready = true) {
  return mount(PptChat, {
    props: { scope: { kind: 'DOCUMENT' }, scopeLabel: '整份演示文稿', ready },
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
  it('does not show the generic completion detail beside a completed discussion reply', async () => {
    const store = usePptStore()
    const detail = '本轮模型已完成；制作结果以作品检查和作业状态为准'
    store.messages = [{ ...message('discussion-complete', '面向开发者，重点介绍架构和 API。'), detail }]
    store.agent = { ...pptAgent(), state: 'COMPLETED', detail }
    const wrapper = render(false)
    await flushPromises()
    expect(wrapper.text()).toContain('面向开发者，重点介绍架构和 API。')
    expect(wrapper.text()).not.toContain(detail)
    expect(wrapper.findAll('.ppt-notice')).toHaveLength(0)
    wrapper.unmount()
  })
  it('keeps a specific completion check failure visible', async () => {
    const store = usePptStore()
    const detail = '模型已停止；制作完成检查未通过，请检查页面问题后继续修改或重试完成制作'
    store.messages = [{ ...message('discussion-check-failed', '已整理出初稿。'), state: 'COMPLETED', detail }]
    store.agent = { ...pptAgent(), state: 'COMPLETED', detail }
    const wrapper = render(false)
    await flushPromises()
    expect(wrapper.text()).toContain(detail)
    expect(wrapper.findAll('.ppt-notice')).toHaveLength(1)
    wrapper.unmount()
  })
  it('keeps the composer open for freeform discussion and confirms the persisted transcript on demand', async () => {
    const store = usePptStore()
    const send = vi.spyOn(store, 'send').mockResolvedValue(true)
    const generate = vi.spyOn(store, 'generate')
    const confirm = vi.spyOn(store, 'confirmRequirements').mockResolvedValue(true)
    const wrapper = render(false)
    await wrapper.get('.ppt-composer textarea').setValue('补充：面向开发者，强调架构和 API')
    await wrapper.get('.ppt-composer').trigger('submit')
    expect(send).toHaveBeenCalledWith('补充：面向开发者，强调架构和 API', { kind: 'DOCUMENT' })
    expect(generate).not.toHaveBeenCalled()
    store.messages = [message('discussion-complete')]
    await flushPromises()
    expect(wrapper.text()).toContain('继续讨论')
    expect(wrapper.text()).toContain('确认需求并执行')
    await wrapper.get('.ppt-composer textarea').setValue('还有新的要求')
    expect(wrapper.findAll('button').some(button => button.text().includes('确认需求并执行'))).toBe(false)
    await wrapper.get('.ppt-composer textarea').setValue('')
    await wrapper.findAll('button').find(button => button.text().includes('确认需求并执行'))!.trigger('click')
    expect(confirm).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it('requires an explicit confirmation card before starting design and keeps clarification replies separate', async () => {
    const store = usePptStore()
    const reply = vi.spyOn(store, 'reply').mockResolvedValue(true)
    const question = { id: 'brief', kind: 'CLARIFICATION' as const, prompt: '本次重点讲什么？', options: ['业务成果'], state: 'PENDING' as const, answer: null, version: 1 }
    store.agent = { ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'CLARIFYING', questions: [question] }
    const wrapper = render()
    expect(wrapper.text()).not.toContain('确认需求并执行')
    await wrapper.get('textarea').setValue('突出业务成果')
    await wrapper.get('.ppt-question').trigger('submit')
    expect(reply).toHaveBeenLastCalledWith(question, '突出业务成果', undefined)
    store.agent = { ...pptAgent(), state: 'WAITING_INPUT', requirementsState: 'AWAITING_CONFIRMATION', questions: [{ ...question, id: 'confirm', kind: 'REQUIREMENTS_CONFIRMATION', prompt: '## 汇报需求\n面向管理层，10 页，商务风格。' }] }
    await flushPromises()
    expect(wrapper.get('.ppt-requirements-confirmation .markdown-document').text()).toContain('10 页')
    const confirm = wrapper.findAll('button').find(button => button.text().includes('确认需求并执行'))!
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
    expect(wrapper.text()).toContain('确认需求并执行')
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

  it('explicitly adjusts a stopped authorization without sending a manual request', async () => {
    const store = usePptStore()
    store.generation = pptGeneration('FAILED')
    store.agent = { ...pptAgent(), state: 'FAILED' }
    const adjusted = vi.spyOn(store, 'adjustAndResume').mockResolvedValue(true)
    const send = vi.spyOn(store, 'send')
    const wrapper = render(false)
    await wrapper.get('.ppt-composer textarea').setValue('先完成八页，去掉示例截图')
    expect(wrapper.text()).toContain('调整要求并继续')
    await wrapper.get('.ppt-composer').trigger('submit')
    expect(adjusted).toHaveBeenCalledWith('先完成八页，去掉示例截图')
    expect(send).not.toHaveBeenCalled()
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })
})
