import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SettingsView from '@/views/SettingsView.vue'
import { api } from '@/api/client'
import { demoRuntime } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'

afterEach(() => vi.restoreAllMocks())

describe('Settings model selection', () => {
  it('loads CLI models into dropdowns and persists the selected model', async () => {
    const current = { cliPath: 'opencode', allowedRoot: '', provider: 'opencode', model: 'model-a', maxTaskAttempts: 12, timeoutMinutes: 30, autoApprove: false }
    vi.spyOn(api, 'getSettings').mockResolvedValue(current)
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([
      { id: 'opencode/big-pickle', provider: 'opencode', model: 'big-pickle', label: 'opencode / big-pickle' },
      { id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'opencode / model-a' },
      { id: 'deepseek/deepseek-chat', provider: 'deepseek', model: 'deepseek-chat', label: 'deepseek / deepseek-chat' },
    ])
    const update = vi.spyOn(api, 'updateSettings').mockResolvedValue(current)
    const wrapper = mount(SettingsView, {
      global: {
        plugins: [createPinia(), ElementPlus],
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          Icon: true,
        },
      },
    })

    await flushPromises()

    expect(wrapper.findAllComponents(ElSelect)).toHaveLength(2)
    expect(wrapper.text()).toContain('model-a')
    await wrapper.get('.settings-save').trigger('click')
    await flushPromises()
    expect(update).toHaveBeenCalledWith(expect.objectContaining({ provider: 'opencode', model: 'model-a' }))
  })

  it('toggles demo data off and reloads the real overview', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    vi.spyOn(api, 'getSettings').mockResolvedValue({ cliPath: 'opencode', allowedRoot: '', provider: 'opencode', model: 'model-a', maxTaskAttempts: 12, timeoutMinutes: 30, autoApprove: false })
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([{ id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'opencode / model-a' }])
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    vi.spyOn(api, 'getTasks').mockResolvedValue([])
    vi.spyOn(api, 'getRuntime').mockResolvedValue({ ...demoRuntime, pid: 9001, endpoint: '127.0.0.1:4096' })
    const wrapper = mount(SettingsView, {
      global: {
        plugins: [pinia, ElementPlus],
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          Icon: true,
        },
      },
    })
    await flushPromises()
    const store = useTaskStore()

    await wrapper.get('.settings-demo button').trigger('click')
    await flushPromises()
    expect(store.usingDemo).toBe(true)
    expect(store.error).toBeUndefined()
    expect(wrapper.get('.settings-demo button').text()).toContain('退出演示数据')

    await wrapper.get('.settings-demo button').trigger('click')
    await flushPromises()
    expect(store.usingDemo).toBe(false)
    expect(store.runtime?.pid).toBe(9001)
    expect(wrapper.get('.settings-demo button').text()).toContain('启用演示数据')
  })
})
