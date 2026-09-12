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
  it('resolves encoded Chinese detail and parent links within the exact bundle', async () => {
    const main = '代码审查_示例项目_20260905-20260911_001.md'
    const detail = '代码审查明细_示例项目_20260905-20260911_001/提交审查_示例项目_20260905-20260911_001.md'
    const metadata = { repairRound: 0, bundleId: 'attempt', reportRole: 'SUMMARY', directoryName: main.slice(0, -3), mainPath: main }
    const artifacts: Artifact[] = [
      { ...reports[0]!, id: 'main', title: main, metadata },
      { ...reports[0]!, id: 'wrong', title: detail, metadata: { ...metadata, bundleId: 'other-attempt', reportRole: 'DETAIL' } },
      { ...reports[0]!, id: 'detail', title: detail, metadata: { ...metadata, reportRole: 'DETAIL' } },
    ]
    const read = vi.spyOn(api, 'getArtifactContent').mockImplementation(async (_task, id) => ({ id, kind: 'TEMPLATE_REPORT', content: id === 'main'
      ? `# 总结\n[详细报告](${detail.split('/').map(encodeURIComponent).join('/')})`
      : `# 明细\n[返回总结](../${encodeURIComponent(main)})`, metadata: {} }))
    const wrapper = mount(TemplateReportsPanel, { props: { taskId: 'task', artifacts, accepted: true }, global: { plugins: [ElementPlus] } })
    await wrapper.findAll('button').find(button => button.text() === '查看最新总结')!.trigger('click')
    await flushPromises()
    expect(read).toHaveBeenLastCalledWith('task', 'main')
    await wrapper.get('a').trigger('click'); await flushPromises()
    expect(read).toHaveBeenLastCalledWith('task', 'detail')
    await wrapper.get('a').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('总结')
    expect(read).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('downloads the selected bundle with its named folder and keeps body reads lazy', async () => {
    const filename = '代码审查_项目_20260905-20260911_002'
    const artifact = { ...reports[1]!, title: `${filename}.md`, metadata: { ...reports[1]!.metadata, bundleId: 'attempt', directoryName: filename, reportRole: 'SUMMARY' } }
    vi.spyOn(api, 'getArtifactContent').mockResolvedValue({ id: artifact.id, kind: 'TEMPLATE_REPORT', content: '# 总结', metadata: {} })
    const zip = vi.spyOn(api, 'downloadTemplateReport').mockResolvedValue(new Blob(['zip']))
    const urls = vi.fn().mockReturnValue('blob:report')
    vi.stubGlobal('URL', class extends URL { static createObjectURL = urls; static revokeObjectURL = vi.fn() })
    let savedName = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { savedName = this.download })
    const wrapper = mount(TemplateReportsPanel, { props: { taskId: 'task', artifacts: [artifact], accepted: true }, global: { plugins: [ElementPlus] } })
    expect(zip).not.toHaveBeenCalled()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', artifact.id); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '下载整套报告')!.trigger('click'); await flushPromises()
    expect(zip).toHaveBeenCalledWith('task', artifact.id)
    expect(savedName).toBe(`${filename}.zip`)
    wrapper.unmount(); vi.unstubAllGlobals()
  })

  it('does not navigate outside the bundle for unresolved or escaping local links', async () => {
    vi.spyOn(api, 'getArtifactContent').mockResolvedValue({ id: 'report', kind: 'TEMPLATE_REPORT', content: '[missing](../../another.md)', metadata: {} })
    const wrapper = mount(TemplateReportsPanel, { props: { taskId: 'task', artifacts: reports, accepted: true }, global: { plugins: [ElementPlus] } })
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'report-0'); await flushPromises()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    wrapper.get('a').element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    wrapper.unmount()
  })

})
