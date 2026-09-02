import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import ToolsView from './ToolsView.vue'

afterEach(() => vi.restoreAllMocks())
describe('MCP tools page', () => {
  it('shows live statuses and reads tool descriptions on expansion without rendering HTML', async () => {
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    vi.spyOn(api, 'getMcpServers').mockResolvedValue({ servers: [
      { id: 'search', name: 'Code search', status: 'connected', type: 'local' },
      { id: 'paused', name: 'Paused tools', status: 'disabled', type: 'remote' },
    ], checkedAt: '2026-09-02T00:00:00Z', complete: true })
    const read = vi.spyOn(api, 'getMcpTools').mockResolvedValue({ tools: [{ name: 'find', description: '<script>untrusted()</script>Find code' }], complete: true })
    const wrapper = mount(ToolsView, { global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: true } } })
    await flushPromises()
    expect(wrapper.findAll('.el-select__selected-item').map(item => item.text())).toContain('全局运行环境')
    expect(wrapper.text()).toContain('已连接'); expect(wrapper.text()).toContain('已停用')
    expect(read).not.toHaveBeenCalled()
    const details = wrapper.get('details'); (details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle'); await flushPromises()
    expect(read).toHaveBeenCalledWith('', 'search')
    expect(wrapper.text()).toContain('Find code'); expect(wrapper.find('script').exists()).toBe(false)
    await wrapper.get('input[aria-label="搜索 MCP 和已读取工具"]').setValue('find')
    expect(wrapper.findAll('details')).toHaveLength(1)
  })
})
