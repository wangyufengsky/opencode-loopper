import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { usePptStore } from '@/stores/pptStore'
import { pptAgent, pptGeneration } from './pptTestFixtures'
import PptGenerationStatus from './PptGenerationStatus.vue'

beforeEach(() => { setActivePinia(createPinia()) })
describe('PPT requirements progress', () => {
  it('shows discussion and confirmation before design and follows the server confirmation state', async () => {
    const store = usePptStore()
    store.generation = { ...pptGeneration(), detail: '' }
    store.agent = { ...pptAgent(), state: 'RUNNING', requirementsState: 'CLARIFYING' }
    const wrapper = mount(PptGenerationStatus)
    expect(wrapper.get('h2').text()).toBe('先聊清你的想法')
    expect(wrapper.find('.ppt-generation-steps').exists()).toBe(false)
    store.agent = { ...store.agent, state: 'WAITING_INPUT', requirementsState: 'AWAITING_CONFIRMATION' }
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('请确认制作需求')
    expect(wrapper.text()).toContain('才会开始第一轮设计')
    store.agent = { ...store.agent, state: 'RUNNING', requirementsState: 'CONFIRMED' }
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('正在构思内容')
    expect(wrapper.find('.ppt-generation-steps').exists()).toBe(true)
  })
  it('preserves a stopped workflow and resume action while requirements are unconfirmed', async () => {
    const store = usePptStore()
    store.generation = pptGeneration('STOPPED')
    store.agent = { ...pptAgent(), state: 'STOPPED', requirementsState: 'CLARIFYING' }
    const wrapper = mount(PptGenerationStatus)
    expect(wrapper.get('h2').text()).toBe('已暂停')
    expect(wrapper.get('button').text()).toContain('按当前要求继续')
  })
  it('describes the pre-generation freeform discussion and the explicit execution action', () => {
    const store = usePptStore()
    store.document = { ...store.document!, phase: 'BRIEFING' }
    store.generation = null
    store.agent = { ...pptAgent(), state: 'COMPLETED' }
    const wrapper = mount(PptGenerationStatus)
    expect(wrapper.get('h2').text()).toBe('需求讨论中')
    expect(wrapper.text()).toContain('确认需求并执行')
  })
})
