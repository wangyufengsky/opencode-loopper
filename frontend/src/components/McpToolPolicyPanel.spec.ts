import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import McpToolPolicyPanel from './McpToolPolicyPanel.vue'
afterEach(() => vi.restoreAllMocks())
describe('tool policy', () => {
  it('uses the project revision and retains server state when a save conflicts', async () => {
    vi.spyOn(api, 'getMcpToolPolicies').mockResolvedValue({ complete: true, detail: '', tools: [{ name: 'read_document', configurable: true, writes: false, globalEnabled: true, projectOverride: 'INHERIT', enabled: true, source: 'GLOBAL', globalVersion: 3, projectVersion: -1 }] })
    const save = vi.spyOn(api, 'updateMcpToolPolicy').mockRejectedValue(new Error('配置已改变，请刷新'))
    const wrapper = mount(McpToolPolicyPanel, { props: { projectId: 'project', serverId: '@loopper-assist' }, global: { plugins: [ElementPlus] } })
    await flushPromises(); wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 0); await flushPromises()
    expect(save).toHaveBeenCalledWith({ projectId: 'project', serverId: '@loopper-assist', toolName: 'read_document', enabled: 0, version: -1 })
    expect(wrapper.text()).toContain('请刷新'); expect(wrapper.text()).toContain('继承全局'); wrapper.unmount()
  })
  it('renders required tools without switches', async () => {
    vi.spyOn(api, 'getMcpToolPolicies').mockResolvedValue({ complete: true, detail: '', tools: [{ name: 'submit_candidate', configurable: false, writes: false, globalEnabled: true, projectOverride: 'INHERIT', enabled: true, source: 'SYSTEM', globalVersion: -1, projectVersion: -1 }] })
    const wrapper = mount(McpToolPolicyPanel, { props: { projectId: '', serverId: '@loopper-internal' }, global: { plugins: [ElementPlus] } }); await flushPromises()
    expect(wrapper.find('.el-select').exists()).toBe(false); expect(wrapper.text()).toContain('不可关闭'); wrapper.unmount()
  })
})
