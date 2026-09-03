import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import ElementPlus from 'element-plus'
import { api } from '@/api/client'
import StoryBindingSetup from './StoryBindingSetup.vue'
import type { StoryBindingCapability } from '@/types/domain'

const available: StoryBindingCapability = { available: true, state: 'AVAILABLE', reason: '已检测到 aicoding', checkedAt: 'now' }
afterEach(() => vi.restoreAllMocks())
function setup() {
  return mount(StoryBindingSetup, {
    props: { projectId: 'p1', modelValue: { enabled: false } },
    global: { plugins: [ElementPlus] },
  })
}

describe('StoryBindingSetup', () => {
  it('disables while detecting or absent and allows explicit recheck after installation', async () => {
    let finish!: (value: StoryBindingCapability) => void
    const probe = vi.spyOn(api, 'getStoryBindingCapability').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = setup()
    expect(wrapper.get('[role="switch"]').attributes('aria-disabled')).toBe('true')
    finish({ ...available, available: false, reason: '当前 OpenCode 未注册 aicoding 命令' })
    await flushPromises()
    expect(wrapper.text()).toContain('未注册')
    expect(wrapper.get('[role="switch"]').attributes('aria-disabled')).toBe('true')
    probe.mockResolvedValue(available)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="switch"]').attributes('aria-disabled')).toBe('false')
  })

  it('preserves identifier leading zeros and only shows fields after enabling', async () => {
    vi.spyOn(api, 'getStoryBindingCapability').mockResolvedValue(available)
    const wrapper = setup()
    await flushPromises()
    expect(wrapper.text()).toContain('仅统计设计师和执行者的 AI 工作量')
    expect(wrapper.find('#story-code').exists()).toBe(false)
    await wrapper.setProps({ modelValue: { enabled: true, systemCode: 'SYS-001', storyCode: '000123' } })
    expect((wrapper.get('#story-code').element as HTMLInputElement).value).toBe('000123')
    await wrapper.get('#story-code').setValue('000456')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({ enabled: true, systemCode: 'SYS-001', storyCode: '000456' })
  })

  it('rejects stale project results, refreshes for runtime generation changes, and exposes failures', async () => {
    let first!: (value: StoryBindingCapability) => void
    const probe = vi.spyOn(api, 'getStoryBindingCapability')
      .mockImplementationOnce(() => new Promise(resolve => { first = resolve }))
      .mockResolvedValueOnce({ ...available, available: false, reason: '第二个项目无插件' })
    const wrapper = setup()
    await wrapper.setProps({ projectId: 'p2' })
    await flushPromises()
    first(available)
    await flushPromises()
    expect(wrapper.text()).toContain('第二个项目无插件')
    probe.mockRejectedValueOnce(new Error('连接断开'))
    await wrapper.setProps({ runtimeIdentity: 'generation-2' })
    await flushPromises()
    expect(wrapper.text()).toContain('连接断开')
    expect(wrapper.get('[role="switch"]').attributes('aria-disabled')).toBe('true')
    expect(probe).toHaveBeenLastCalledWith('p2')
  })
})
