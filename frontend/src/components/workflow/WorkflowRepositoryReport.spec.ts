import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowRepositoryReport from './WorkflowRepositoryReport.vue'
const report = { version: 1, type: 'REPOSITORY_SOURCE', complete: true, branchId: 'remote:origin:refs/heads/main', commitSha: 'a'.repeat(40), projectPrefix: 'server', fileCount: 12, excludedCount: 1 }
describe('固定分支代码报告', () => {
  it('显示精确提交与文件限制，转义用户分支名称', () => {
    const view = mount(WorkflowRepositoryReport, { props: { content: { ...report, branchId: 'local:refs/heads/<img src=x>' } } })
    expect(view.text()).toContain('分支代码已固定'); expect(view.text()).toContain(report.commitSha)
    expect(view.text()).toContain('清单 12 项 · 限制读取 1 项'); expect(view.text()).toContain('本地 · <img src=x>'); expect(view.find('img').exists()).toBe(false)
  })
  it('失败报告只提供恢复办法，不把上一份摘要当作成功', () => {
    const view = mount(WorkflowRepositoryReport, { props: { content: { ...report, complete: false, code: 'WORKFLOW_REPOSITORY_CAPTURE_FAILED' } } })
    expect(view.text()).toContain('采集未完成'); expect(view.text()).toContain('已定位的提交保持不变')
    expect(view.text()).not.toContain('分支代码已固定'); expect(view.text()).not.toContain(report.commitSha); expect(view.text()).not.toContain('WORKFLOW_REPOSITORY')
  })
  it.each([null, {}, { ...report, version: 2 }, { ...report, commitSha: 'latest' }, { ...report, excludedCount: 13 }, { ...report, fileCount: 50001 }, { ...report, projectPrefix: null }])('不完整或矛盾的成功报告不能显示完成', content => {
    const view = mount(WorkflowRepositoryReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('分支代码已固定')
  })
})
