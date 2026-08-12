import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import RuntimeView from '@/views/RuntimeView.vue'
import { useTaskStore } from '@/stores/taskStore'

describe('RuntimeView managed startup diagnostics', () => {
  it('shows the launch failure and labels the random port as an attempted address', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.runtime = {
      status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234', model: '', checkedAt: '2026-08-12T06:00:00Z',
      startupFailure: 'Managed OpenCode exited with code 1 before it became healthy',
    }

    const wrapper = mount(RuntimeView, {
      global: {
        plugins: [pinia, ElementPlus],
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          Icon: true,
          StatusBadge: { props: ['status'], template: '<span>{{ status }}</span>' },
        },
      },
    })

    expect(wrapper.get('.runtime-startup-error').text()).toContain('OpenCode 自动启动失败')
    expect(wrapper.get('.runtime-startup-error').text()).toContain('exited with code 1')
    expect(wrapper.text()).toContain('尝试地址')
    expect(wrapper.text()).toContain('http://127.0.0.1:51234')
    expect(wrapper.text()).toContain('未建立受管进程')
    expect(wrapper.text()).not.toContain('外部复用服务')
    expect(wrapper.get('.start-runtime-button').text()).toContain('启动 OpenCode 并检查连接')
  })

  it('starts OpenCode explicitly and reports success only after the checked snapshot is online', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.runtime = {
      status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234', model: '', checkedAt: '2026-08-12T06:00:00Z',
      startupFailure: 'Managed OpenCode did not become healthy before startup-timeout',
    }
    const started = {
      status: 'ONLINE' as const, managed: true, pid: 6400, endpoint: 'http://127.0.0.1:34020',
      version: '1.18.16', model: '', checkedAt: '2026-08-12T06:30:00Z',
    }
    const start = vi.spyOn(store, 'startRuntime').mockImplementation(async () => {
      store.runtime = started
      return started
    })
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }) as never)

    const wrapper = mount(RuntimeView, {
      global: {
        plugins: [pinia, ElementPlus],
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          Icon: true,
          StatusBadge: { props: ['status'], template: '<span>{{ status }}</span>' },
        },
      },
    })

    await wrapper.get('.start-runtime-button').trigger('click')
    await flushPromises()

    expect(start).toHaveBeenCalledOnce()
    expect(success).toHaveBeenCalledWith('OpenCode 已启动并通过连接检查')
    expect(wrapper.text()).toContain('OpenCode 1.18.16')
    expect(wrapper.text()).toContain('http://127.0.0.1:34020')
    expect(wrapper.text()).not.toContain('OpenCode 自动启动失败')
    success.mockRestore()
  })
})
