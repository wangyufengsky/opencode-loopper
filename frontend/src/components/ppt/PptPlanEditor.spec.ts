import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PptPlanEditor from './PptPlanEditor.vue'
import { pptCapabilities, pptPlan } from './pptTestFixtures'
describe('PPT plan editing', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })
  afterEach(() => {
    vi.useRealTimers()
  })
  it('loads confirmed requirements and preserves unsaved input across a conflicting revision', async () => {
    const wrapper = mount(PptPlanEditor, {
      props: {
        plan: pptPlan(),
        revision: 3,
        capabilities: pptCapabilities(),
      },
    })
    const audience = wrapper.findAll('input')[0]!
    expect((audience.element as HTMLInputElement).value).toBe('管理层')
    await audience.setValue('产品团队')
    await wrapper.setProps({
      revision: 4,
      plan: {
        ...pptPlan(),
        brief: {
          ...pptPlan().brief,
          audience: '最新受众',
        },
      },
    })
    expect((audience.element as HTMLInputElement).value).toBe('产品团队')
    expect(wrapper.text()).toContain('当前输入已保留')
    expect(wrapper.get('button.ppt-primary').attributes('disabled')).toBeDefined()
    await wrapper.get('.ppt-notice button').trigger('click')
    expect((audience.element as HTMLInputElement).value).toBe('最新受众')
    wrapper.unmount()
  })
  it('autosaves after editing pauses, preserves unknown fields, and never confirms a phase', async () => {
    vi.useFakeTimers()
    const plan = {
      ...pptPlan(),
      customPolicy: {
        retained: true,
      },
      delivery: {
        fileName: '汇报.pptx',
        targetSoftware: 'WPS',
        includeNotes: false,
        customHint: '保留',
      },
    }
    const wrapper = mount(PptPlanEditor, {
      props: {
        plan,
        documentId: 'autosave',
        revision: 3,
        capabilities: pptCapabilities(),
      },
    })
    await wrapper.findAll('input')[0]!.setValue('业务部门')
    await vi.advanceTimersByTimeAsync(899)
    expect(wrapper.emitted('save')).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1)
    const [saved, revision] = wrapper.emitted('save')![0]!
    expect(saved).toMatchObject({
      customPolicy: {
        retained: true,
      },
      delivery: {
        includeNotes: false,
        customHint: '保留',
      },
      brief: {
        audience: '业务部门',
      },
    })
    expect(revision).toBe(3)
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.emitted('save')).toHaveLength(1)
    expect(Object.keys(wrapper.emitted()).filter((name) => name.includes('confirm'))).toEqual([])
    wrapper.unmount()
  })
  it('restores an unsaved edit after remount without overwriting a newer server plan', async () => {
    vi.useFakeTimers()
    const wrapper = mount(PptPlanEditor, {
      props: {
        plan: pptPlan(),
        documentId: 'restore',
        revision: 3,
        capabilities: pptCapabilities(),
      },
    })
    await wrapper.findAll('input')[0]!.setValue('本地草稿')
    wrapper.unmount()
    const restored = mount(PptPlanEditor, {
      props: {
        plan: pptPlan(),
        documentId: 'restore',
        revision: 4,
        capabilities: pptCapabilities(),
      },
    })
    expect((restored.findAll('input')[0]!.element as HTMLInputElement).value).toBe('本地草稿')
    await vi.advanceTimersByTimeAsync(2000)
    expect(restored.emitted('save')).toBeUndefined()
    expect(restored.text()).toContain('自动保存已暂停')
    restored.unmount()
  })
})
