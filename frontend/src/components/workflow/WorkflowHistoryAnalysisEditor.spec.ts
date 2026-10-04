import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import type { WorkflowNode } from '@/types/domain'
import { newNode } from './graph'
import { WorkflowNodeEditor as WorkflowNodeEditor } from '@/pages/w5/workflow/WorkflowNodeEditor'
function render(moduleId: string, disabled = false) {
  const node = { ...newNode('free.readonly'), moduleId, parameters: { retained: 'keep' }, outputs: [{ name: 'analysis', title: '历史分析', kind: 'JSON' as const, required: true }] }
  return mount(WorkflowNodeEditor, { props: { node, disabled, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } } })
}
describe('历史专业节点配置', () => {
  it('使用可读批次序号并保留其他参数，交付物与完成语义保持专业合同', async () => {
    const view = render('history.review'); await view.get('[aria-label="历史审查批次"]').setValue(3)
    const changed = view.emitted('change')!.at(-1)![0] as WorkflowNode
    expect(changed.parameters).toEqual({ retained: 'keep', historyBatchOrdinal: '2' }); expect(view.get('[aria-label="专业交付物"]').text()).toContain('历史分析')
    expect(view.text()).not.toContain('添加交付物'); expect(view.text()).not.toContain('添加业务结果'); expect(view.find('option[value="OUTCOME"]').exists()).toBe(false)
  })
  it('贡献者使用邮箱选择，锁定后不能修改', async () => {
    const view = render('history.contribution'); await view.get('[aria-label="贡献者邮箱"]').setValue('person@example.test')
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters.historyContributorEmail).toBe('person@example.test')
    const locked = render('history.contribution', true); await locked.get('[aria-label="贡献者邮箱"]').setValue('changed'); expect(locked.emitted('change')).toBeUndefined()
  })
  it('报告类型由用户选择并保留其余配置，锁定节点不能改动', async () => {
    const view = render('system.history.report'); await view.get('[aria-label="历史报告类型"]').setValue('CONTRIBUTION_REPORT')
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters).toEqual({ retained: 'keep', historyReportKind: 'CONTRIBUTION_REPORT' })
    const locked = render('system.history.report', true); await locked.get('[aria-label="历史报告类型"]').setValue('CONTRIBUTION_REPORT'); expect(locked.emitted('change')).toBeUndefined()
    expect(view.text()).toContain('全部适用分析完整覆盖')
  })

})
