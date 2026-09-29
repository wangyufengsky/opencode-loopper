import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WorkflowNode } from '@/types/domain'
import { newNode } from './graph'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
function render(moduleId: string, disabled = false) {
  const node = { ...newNode('free.readonly'), moduleId, parameters: { retained: 'keep' }, outputs: [{ name: 'analysis', title: '代码分析', kind: 'JSON' as const, required: true }] }
  return mount(WorkflowNodeEditor, { props: { node, disabled, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
}
describe('版本审查节点配置', () => {
  it('批次从 1 显示并保留参数，锁定后不修改', async () => {
    const view = render('snapshot.analyze'); await view.get('[aria-label="版本分析批次"]').setValue(3)
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters).toEqual({ retained: 'keep', snapshotBatchOrdinal: '2' })
    expect(view.get('[aria-label="专业交付物"]').text()).toContain('代码分析'); expect(view.text()).not.toContain('添加业务结果'); expect(view.find('option[value="OUTCOME"]').exists()).toBe(false)
    const locked = render('snapshot.analyze', true); await locked.get('[aria-label="版本分析批次"]').setValue(5); expect(locked.emitted('change')).toBeUndefined()
  })
  it('支持显式绕过复用且保存其他参数，锁定后不能改动', async () => {
    const view = render('snapshot.analyze'); expect((view.get('[aria-label="历史分析复用"]').element as HTMLSelectElement).value).toBe('ALLOW')
    await view.get('[aria-label="历史分析复用"]').setValue('BYPASS'); expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters).toEqual({ retained: 'keep', snapshotReuse: 'BYPASS' })
    const locked = render('snapshot.analyze', true); await locked.get('[aria-label="历史分析复用"]').setValue('BYPASS'); expect(locked.emitted('change')).toBeUndefined()
  })
  it('报告允许明确取消复核要求并保留其他配置，锁定后不可改变', async () => {
    const view = render('system.snapshot.report'); await view.get('[aria-label="版本报告复核策略"]').setValue('NONE')
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters).toEqual({ retained: 'keep', reviewPolicy: 'NONE' })
    expect(view.text()).toContain('未复核的问题仍作为候选保留')
    const locked = render('system.snapshot.report', true); await locked.get('[aria-label="版本报告复核策略"]').setValue('NONE'); expect(locked.emitted('change')).toBeUndefined()
  })
  it('独立复核继承绑定的分析范围，可由用户删除', () => {
    const view = render('snapshot.review'); expect(view.find('[aria-label="版本分析批次"]').exists()).toBe(false); expect(view.text()).toContain('无需复核时可以删除本节点')
  })
})
