import { beforeEach, describe, expect, it } from 'vitest'
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
