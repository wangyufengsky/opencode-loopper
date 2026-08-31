import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import RuntimeView from '@/views/RuntimeView.vue'
import { useTaskStore } from '@/stores/taskStore'

describe('RuntimeView managed startup diagnostics', () => {
  it('keeps capability and authorization details out of the compact runtime overview', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.runtime = {
      loopperVersion: '0.1.75', status: 'ONLINE', version: '1.18.18', managed: true, pid: 71386,
      endpoint: 'http://127.0.0.1:55389', model: 'opencode-go/deepseek-v4-flash', checkedAt: '2026-08-18T04:03:00Z',
      generation: '11111111',
      internalMcp: {
        status: 'CONNECTED', configured: true, detail: undefined,
      },
      capabilities: {
        agentDiscovery: 'AVAILABLE', agents: [{ name: 'plan' }], nativePlanAgent: true,
        structuredOutputTransport: 'UNAVAILABLE', selectedModelStructuredOutput: 'UNKNOWN',
        defaultResponseMode: 'TEXT_MARKER', extensionPolicy: 'TRUSTED_ALLOWED', checkedAt: '2026-08-18T04:03:00Z',
      },
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

    expect(wrapper.text()).toContain('受管进程')
    expect(wrapper.text()).not.toContain('OpenCode 可复用能力')
    expect(wrapper.text()).not.toContain('执行授权边界')
    expect(wrapper.text()).not.toContain('NATIVE CAPABILITY DISCOVERY')
    expect(wrapper.text()).not.toContain('SAFETY GUARDRAILS')
    expect(wrapper.text()).toContain('受管代次')
    expect(wrapper.text()).toContain('11111111')
    expect(wrapper.text()).toContain('内部 MCP')
    expect(wrapper.text()).toContain('已就绪')
    expect(wrapper.text()).toContain('配置已注入')
    expect(wrapper.text()).not.toContain('11111111-2222-3333-4444-555555555555')
    expect(wrapper.text()).not.toContain('loopper_internal_private123')
    expect(wrapper.text()).not.toContain('bearer')
  })

  it('shows the launch failure and labels the random port as an attempted address', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.runtime = {
      loopperVersion: '0.1.53', status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234', model: '', checkedAt: '2026-08-12T06:00:00Z',
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

    expect(wrapper.get('.runtime-startup-error').text()).toContain('OpenCode 启动失败')
    expect(wrapper.get('.runtime-startup-error').text()).toContain('请检查配置后重试')
    expect(wrapper.get('.runtime-startup-error').text()).not.toContain('exited with code 1')
    expect(wrapper.text()).toContain('尝试地址')
    expect(wrapper.text()).toContain('http://127.0.0.1:51234')
    expect(wrapper.text()).toContain('未启动')
    expect(wrapper.get('.loopper-version').text()).toContain('Loopper 版本')
    expect(wrapper.get('.loopper-version').text()).toContain('0.1.53')
    expect(wrapper.text()).not.toContain('外部复用服务')
    expect(wrapper.get('.start-runtime-button').text()).toContain('启动并检查连接')
  })

  it('starts OpenCode explicitly and reports success only after the checked snapshot is online', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useTaskStore()
    store.runtime = {
      loopperVersion: '0.1.53', status: 'OFFLINE', managed: false, endpoint: 'http://127.0.0.1:51234', model: '', checkedAt: '2026-08-12T06:00:00Z',
      startupFailure: 'Managed OpenCode did not become healthy before startup-timeout',
    }
    const started = {
      loopperVersion: '0.1.53', status: 'ONLINE' as const, managed: true, pid: 6400, endpoint: 'http://127.0.0.1:34020',
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
