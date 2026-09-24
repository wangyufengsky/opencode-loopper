import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SettingsView from '@/views/SettingsView.vue'
import { api } from '@/api/client'
import { demoRuntime } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings } from '@/types/domain'

afterEach(() => vi.restoreAllMocks())

function currentSettings(): AppSettings {
  return {
    runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
    openCode: { cliPath: 'opencode', mode: 'managed', baseUrl: 'http://127.0.0.1:4096', provider: 'opencode', model: 'model-a', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
    limits: { maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 30, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
    retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 },
    publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 },
    startupConfigPath: '/tmp/startup-overrides.properties', appliedLiveFields: ['retryWait'], restartRequiredFields: ['runtime.serverPort'],
  }
}

describe('Settings model selection', () => {
  it('opens role management from settings without including it in the save form', async () => {
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings())
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([{ id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'model-a' }])
    const save = vi.spyOn(api, 'updateSettings')
    const wrapper = mount(SettingsView, { global: { plugins: [createPinia(), ElementPlus], stubs: {
      PageHeader: { template: '<header><slot name="actions" /></header>' },
      RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' }, Icon: true,
    } } })
    await flushPromises()
    expect(wrapper.find('a[href="/settings/roles"]').exists()).toBe(false)
    expect(save).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('preserves edits across sections and reveals the field that blocks saving', async () => {
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings())
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([{ id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'model-a' }])
    const update = vi.spyOn(api, 'updateSettings')
    const wrapper = mount(SettingsView, { attachTo: document.body, global: { plugins: [createPinia(), ElementPlus], stubs: { PageHeader: { template: '<header><slot name="actions" /></header>' }, Icon: true } } })
    await flushPromises()
    const path = wrapper.findAllComponents({ name: 'ElFormItem' }).find(item => item.props('label') === '允许项目根（立即生效）')!.get('input')
    await path.setValue('relative/path')
    await wrapper.get('[aria-controls="settings-models"]').trigger('click')
    expect(wrapper.get('#settings-runtime').isVisible()).toBe(false)
    expect(wrapper.get('#settings-models').isVisible()).toBe(true)
    await wrapper.get('.settings-save').trigger('click')
    expect(wrapper.get('#settings-runtime').isVisible()).toBe(true)
    expect(wrapper.get('#settings-runtime').text()).toContain('允许项目根必须是绝对路径')
    expect((path.element as HTMLInputElement).value).toBe('relative/path')
    expect(update).not.toHaveBeenCalled()
    await path.setValue('/workspace')
    const cli = wrapper.findAllComponents({ name: 'ElFormItem' }).find(item => item.props('label') === '命令行路径（下次会话生效）')!.get('input')
    await cli.setValue('')
    await wrapper.get('.settings-save').trigger('click')
    expect(wrapper.get('#settings-models').isVisible()).toBe(true)
    expect(wrapper.get('#settings-models').text()).toContain('OpenCode CLI 路径不能为空')
    expect(update).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps all execution limit controls in the bottom-aligned limits grid', async () => {
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings())
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([
      { id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'opencode / model-a' },
    ])
    const wrapper = mount(SettingsView, {
      global: {
        plugins: [createPinia(), ElementPlus],
        stubs: { PageHeader: { template: '<header><slot name="actions" /></header>' }, Icon: true },
      },
    })

    await flushPromises()

    const limits = wrapper.get('.limits-grid')
    expect(wrapper.get('.timeout-policy-control').text()).toContain('启用业务超时限制')
    expect(limits.text()).not.toContain('尝试超时（分钟）')
    wrapper.get('.timeout-policy-control').findComponent({ name: 'ElSwitch' }).vm.$emit('update:modelValue', true)
    await flushPromises()
    expect(limits.findAll('.el-form-item')).toHaveLength(8)
    expect(limits.text()).toContain('尝试超时（分钟）')
    expect(limits.text()).toContain('验证超时（分钟）')
    expect(limits.text()).toContain('设计超时（分钟）')
  })

  it('loads CLI models into dropdowns and persists the selected model', async () => {
    const current = currentSettings()
    vi.spyOn(api, 'getSettings').mockResolvedValue(current)
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([
      { id: 'opencode/big-pickle', provider: 'opencode', model: 'big-pickle', label: 'opencode / big-pickle' },
      { id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'opencode / model-a' },
      { id: 'deepseek/deepseek-chat', provider: 'deepseek', model: 'deepseek-chat', label: 'deepseek / deepseek-chat' },
    ])
    const saved = { ...current, openCode: { ...current.openCode, model: 'big-pickle' } }
    const update = vi.spyOn(api, 'updateSettings').mockResolvedValue(saved)
    const runtime = vi.spyOn(api, 'getRuntime').mockResolvedValue({ ...demoRuntime, model: 'opencode/big-pickle' })
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

    expect(wrapper.findAllComponents(ElSelect)).toHaveLength(3)
    expect(wrapper.text()).toContain('model-a')
    useTaskStore().runtime = { ...demoRuntime, model: 'opencode/model-a' }
    const model = wrapper.findAllComponents({ name: 'ElFormItem' }).find(item => item.props('label') === '模型')!.getComponent(ElSelect)
    model.vm.$emit('update:modelValue', 'big-pickle')
    const concurrency = wrapper.findAllComponents({ name: 'ElFormItem' }).find(item => item.props('label') === '模板分析并发数')!.getComponent({ name: 'ElInputNumber' })
    concurrency.vm.$emit('update:modelValue', 7)
    await wrapper.get('.settings-save').trigger('click')
    await flushPromises()
    expect(runtime).toHaveBeenCalledTimes(1)
    expect(useTaskStore().runtime?.model).toBe('opencode/big-pickle')
    expect(update).toHaveBeenCalledWith(expect.objectContaining({
      openCode: expect.objectContaining({ mode: 'managed', provider: 'opencode', model: 'big-pickle' }),
      limits: expect.objectContaining({ templateAnalysisConcurrency: 7 }),
    }))
  })

  it('toggles demo data off and reloads the real overview', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings())
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

  it('keeps the form available and reports a settings save failure', async () => {
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings())
    vi.spyOn(api, 'getSettingsModels').mockResolvedValue([
      { id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'opencode / model-a' },
    ])
    vi.spyOn(api, 'updateSettings').mockRejectedValue(new Error('配置文件写入失败'))
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const wrapper = mount(SettingsView, {
      global: {
        plugins: [createPinia(), ElementPlus],
        stubs: { PageHeader: { template: '<header><slot name="actions" /></header>' }, Icon: true },
      },
    })
    await flushPromises()

    await wrapper.get('.settings-save').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith('配置文件写入失败')
    expect(wrapper.get('.settings-save').attributes('disabled')).toBeUndefined()
  })
})
