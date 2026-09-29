import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowSourceReport from './WorkflowSourceReport.vue'
const report = { version: 1, type: 'SOURCE_SNAPSHOT', complete: true, sourcePath: 'src', targetCount: 2, fileCount: 5, incompleteCount: 0, excludedCount: 1, exclusions: [{ path: '.env', reason: '受保护文件不提供读取' }] }
describe('冻结源码报告', () => {
  it('展示固定资料统计和排除原因', () => {
    const view = mount(WorkflowSourceReport, { props: { content: report } })
    expect(view.text()).toContain('源码已冻结'); expect(view.text()).toContain('适用目标 2 项'); expect(view.text()).toContain('受保护文件不提供读取')
    expect(view.get('details').attributes('open')).toBeUndefined()
  })
  it('保留未完成事实与恢复办法，并转义路径内容', () => {
    const view = mount(WorkflowSourceReport, { props: { content: { ...report, complete: false, incompleteCount: 1, code: 'WORKFLOW_SOURCE_INCOMPLETE', exclusions: [{ path: '<img src=x onerror=alert(1)>', reason: '无法解析为 UTF-8 文本' }] } } })
    expect(view.text()).toContain('采集未完成'); expect(view.text()).toContain('未完整读取 1 项'); expect(view.text()).toContain('新增采集节点')
    expect(view.get('details').attributes('open')).toBeDefined(); expect(view.find('img').exists()).toBe(false); expect(view.text()).not.toContain('WORKFLOW_SOURCE_INCOMPLETE')
  })
  it('正文未写全时说明原版本恢复，不把重试当成重新采集', () => {
    const view = mount(WorkflowSourceReport, { props: { content: { ...report, complete: false, code: 'WORKFLOW_SOURCE_CHANGED' } } })
    expect(view.text()).toContain('恢复原源码后重试'); expect(view.text()).not.toContain('源码已冻结')
  })
  it.each([null, {}, { ...report, version: 2 }, { ...report, complete: 'yes' }, { ...report, incompleteCount: 1 }, { ...report, targetCount: 0 }])('未知或矛盾报告不显示完成', content => {
    const view = mount(WorkflowSourceReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('源码已冻结')
  })
})
