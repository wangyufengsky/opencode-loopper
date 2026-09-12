import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, it, expect, vi } from 'vitest'
import { api } from '@/api/client'
import type { Artifact } from '@/types/domain'
import TemplateReportsPanel from './TemplateReportsPanel.vue'

const reports: Artifact[] = [0, 1].map(round => ({ id: `report-${round}`, taskId: 'task', kind: 'REPORT', title: 'code-review.md', createdAt: 'now', content: '', metadata: { displayName: '代码审查', repairRound: round } }))
afterEach(() => vi.restoreAllMocks())
describe('template report evidence', () => {
  it('loads only the selected task-owned body and distinguishes superseded versions', async () => {
    const read = vi.spyOn(api, 'getArtifactContent').mockResolvedValue({ id: 'report', kind: 'TEMPLATE_REPORT', content: '# 报告\n具体证据', metadata: {} })
    const wrapper = mount(TemplateReportsPanel, { props: { taskId: 'task', artifacts: reports, accepted: true }, global: { plugins: [ElementPlus] } })
    expect(read).not.toHaveBeenCalled()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'report-0')
    await flushPromises()
    expect(read).toHaveBeenCalledWith('task', 'report-0')
    expect(wrapper.text()).not.toContain('已通过评审')
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'report-1')
    await flushPromises()
    expect(wrapper.text()).toContain('已通过评审')
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'report-0')
    await flushPromises()
    expect(read).toHaveBeenCalledTimes(2)
    await wrapper.setProps({ taskId: 'another', artifacts: [] })
    expect(wrapper.text()).not.toContain('具体证据')
    wrapper.unmount()
  })
  it('opens a contributor link in the same report version without leaving the task', async () => {
    const artifacts: Artifact[] = [...reports, { ...reports[1]!, id: 'person', title: 'contributors/alice.md' }]
    const read = vi.spyOn(api, 'getArtifactContent').mockImplementation(async (_task, id) => ({ id, kind: 'TEMPLATE_REPORT', content: id === 'person' ? '# Alice 周报' : '[Alice](contributors/alice.md)', metadata: {} }))
    const wrapper = mount(TemplateReportsPanel, { props: { taskId: 'task', artifacts, accepted: true }, global: { plugins: [ElementPlus] } })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'report-1')
    await flushPromises()
    await wrapper.get('a').trigger('click')
    await flushPromises()
    expect(read).toHaveBeenLastCalledWith('task', 'person')
    expect(wrapper.text()).toContain('Alice 周报')
    wrapper.unmount()
  })
})
