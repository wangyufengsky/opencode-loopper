import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCanvasRuntime } from './canvasRuntimeVue'
import { CANVAS_RUNTIME_STORAGE } from './canvasRuntime'

const Canvas = defineComponent({ setup() { const runtime = useCanvasRuntime(); return () => h('div', { 'data-runtime': runtime.value }) } })
describe('mounted canvas runtime isolation', () => {
  afterEach(() => { localStorage.removeItem(CANVAS_RUNTIME_STORAGE) })
  it('does not remount or mutate current runtime on preference/storage events', async () => {
    localStorage.removeItem(CANVAS_RUNTIME_STORAGE)
    const wrapper = mount(Canvas), original = wrapper.element
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ documents: 'vue' }))
    window.dispatchEvent(new StorageEvent('storage', { key: CANVAS_RUNTIME_STORAGE }))
    await wrapper.setProps({})
    expect(wrapper.attributes('data-runtime')).toBe('react'); expect(wrapper.element).toBe(original)
    wrapper.unmount()
    const next = mount(Canvas); expect(next.attributes('data-runtime')).toBe('vue'); next.unmount()
  })
  it('keeps React available when localStorage access is denied', () => {
    const getter = vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => { throw new Error('blocked') })
    const wrapper = mount(Canvas); expect(wrapper.attributes('data-runtime')).toBe('react'); wrapper.unmount(); getter.mockRestore()
  })
})
