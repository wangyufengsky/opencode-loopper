import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'

class ResizeObserverStub {
  observe() { /* jsdom has no layout observer */ }
  unobserve() { /* no-op */ }
  disconnect() { /* no-op */ }
}

if (!globalThis.ResizeObserver) vi.stubGlobal('ResizeObserver', ResizeObserverStub)

describe('LoopSpecEditor', () => {
  it('renders an accessible CodeMirror JSON editor and follows external v-model updates', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: '{\n  "goal": "first"\n}', ariaLabel: 'LoopSpec source' } })
    expect(wrapper.find('.cm-editor').exists()).toBe(true)
    expect(wrapper.find('[contenteditable="true"]').attributes('aria-label')).toBe('LoopSpec source')

    await wrapper.setProps({ modelValue: '{\n  "goal": "second"\n}' })
    expect(wrapper.find('.cm-content').text()).toContain('second')
  })
})
