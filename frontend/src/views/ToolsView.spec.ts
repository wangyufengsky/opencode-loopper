import { flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { ToolsPage as ToolsView } from '@/pages/w2/secondary/ToolsPage'
import { coreFixture } from '@/pages/w2/core/coreTestHelpers'

beforeEach(() => { vi.spyOn(api, 'getMcpToolPolicies').mockResolvedValue({ tools: [], complete: true, detail: '' }) })
afterEach(() => vi.restoreAllMocks())
describe('MCP tools page', () => {
  it('keeps Skill discovery lazy and opens the selected Markdown document', async () => {
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    vi.spyOn(api, 'getMcpServers').mockResolvedValue({ servers: [], complete: true, checkedAt: '' })
    const skill = { name: 'review', description: '审查代码', location: '/skills/review/SKILL.md' }
    const readSkills = vi.spyOn(api, 'getSkills').mockResolvedValue({ skills: [skill], complete: true, checkedAt: '' })
    vi.spyOn(api, 'getSkillDocument').mockResolvedValue({ ...skill, content: '# 使用说明' })
    const wrapper = mount(ToolsView, { props: coreFixture().props })
    await flushPromises()
    expect(readSkills).not.toHaveBeenCalled()
    await wrapper.get('#tab-skills').trigger('click'); await flushPromises()
    expect(readSkills).toHaveBeenCalledWith('')
    await wrapper.get('.skill-card').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('使用说明')
    await wrapper.get('#tab-tools').trigger('click'); await flushPromises()
    expect(wrapper.find('.skill-document').exists()).toBe(false)
    wrapper.unmount()
  })
  it('shows live statuses and reads tool descriptions on expansion without rendering HTML', async () => {
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    vi.spyOn(api, 'getMcpServers').mockResolvedValue({ servers: [
      { id: 'search', name: 'Code search', status: 'connected', type: 'local' },
      { id: 'search', name: 'Code search', status: 'connected', type: 'local' },
      { id: 'paused', name: 'Paused tools', status: 'disabled', type: 'remote' },
    ], checkedAt: '2026-09-02T00:00:00Z', complete: true })
    const read = vi.spyOn(api, 'getMcpToolPolicies').mockResolvedValue({ tools: [{ name: 'find', description: '<script>untrusted()</script>Find code', configurable: true, writes: false, globalEnabled: true, projectOverride: 'INHERIT', enabled: true, source: 'GLOBAL', globalVersion: 0, projectVersion: -1 }], complete: true, detail: '' })
    const wrapper = mount(ToolsView, { props: coreFixture().props })
    await flushPromises()
    expect(wrapper.findAll('select option').map(item => item.text())).toContain('全局运行环境')
    expect(wrapper.text()).toContain('已连接'); expect(wrapper.text()).toContain('已停用')
    expect(read).not.toHaveBeenCalled()
    const details = wrapper.findAll('.tool-server').find(d => d.text().includes('Code search'))!; await details.trigger('click'); await flushPromises()
    expect(read).toHaveBeenCalledWith('', 'search')
    expect(wrapper.text()).toContain('Find code'); expect(wrapper.find('script').exists()).toBe(false)
    await wrapper.findAll('label').find(row => row.text().includes('搜索 MCP 和已读取工具'))!.get('input').setValue('find')
    expect(wrapper.findAll('.tool-server')).toHaveLength(1)
    expect(wrapper.findAll('.policy')).toHaveLength(1)
    wrapper.unmount()
  })
})
