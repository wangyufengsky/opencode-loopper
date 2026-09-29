import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WorkflowNode } from '@/types/domain'
import WorkflowReviewSourceEditor from './WorkflowReviewSourceEditor.vue'
const node = (): WorkflowNode => ({ id: 'source', title: '版本资料', kind: 'SYSTEM', moduleId: 'system.review.snapshot', moduleVersion: 1, roleId: null,
  task: '固定代码', inputs: ['branch', 'startDate', 'endDate'].map(name => ({ name, kind: 'TEXT', source: 'REQUIREMENT', sourceId: `custom_${name}`, output: null, required: true })),
  outputs: [], outcomes: [], completion: { kind: 'VERIFIED', criterion: '完整保存', expectedOutcome: null }, maxRetries: 2, pauseAfter: true, parameters: { reviewMode: 'DATE_INCREMENTAL', reviewTimeoutSeconds: '600', retained: 'keep' } })
describe('版本审查采集配置', () => {
  it('切换全面审查仅移除日期绑定，保留分支及用户配置', async () => {
    const original = node(), view = mount(WorkflowReviewSourceEditor, { props: { node: original } })
    await view.get('select').setValue('FULL'); const next = view.emitted('change')![0]![0] as WorkflowNode
    expect(next.inputs).toEqual([original.inputs[0]]); expect(next.parameters).toEqual({ ...original.parameters, reviewMode: 'FULL' })
    expect(next.pauseAfter).toBe(true); expect(next.maxRetries).toBe(2); expect(original.inputs).toHaveLength(3)
    await view.setProps({ node: next }); expect(view.text()).toContain('当前提交'); await view.get('select').setValue('DATE_INCREMENTAL')
    const restored = view.emitted('change')![1]![0] as WorkflowNode; expect(restored.inputs.map(i => i.name)).toEqual(['branch', 'startDate', 'endDate'])
    expect(restored.inputs[0]).toEqual(original.inputs[0])
  })
  it('原模式保持自定义日期绑定，已执行节点不能变更配置', async () => {
    const original = node(), view = mount(WorkflowReviewSourceEditor, { props: { node: original } })
    await view.get('select').trigger('change'); expect((view.emitted('change')![0]![0] as WorkflowNode).inputs).toEqual(original.inputs)
    await view.setProps({ disabled: true }); await view.get('select').setValue('FULL'); await view.get('input').setValue('10')
    expect(view.emitted('change')).toHaveLength(1)
  })
})
