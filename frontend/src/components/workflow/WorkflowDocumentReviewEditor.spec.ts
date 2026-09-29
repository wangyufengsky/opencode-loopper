import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { WorkflowNode } from '@/types/domain'
import { newNode } from './graph'
import WorkflowDocumentReviewEditor from './WorkflowDocumentReviewEditor.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
const node = (parameters: Record<string, string> = {}): WorkflowNode => ({ ...newNode('free.readonly'), moduleId: 'document.direct-review', parameters })
afterEach(() => vi.restoreAllMocks())
describe('原文章节和批次编辑', () => {
  it('用可读序号选择章节，保存原有参数并保持原节点不变', async () => {
    const original = node({ retained: 'keep' }), view = mount(WorkflowDocumentReviewEditor, { props: { node: original } })
    await view.get('select').setValue('SELECTED')
    const selected = view.emitted('change')!.at(-1)![0] as WorkflowNode
    await view.setProps({ node: selected }); await view.get('[aria-label="原文 1 章节序号"]').setValue(3)
    const changed = view.emitted('change')!.at(-1)![0] as WorkflowNode
    expect(JSON.parse(changed.parameters.documentSections!)).toEqual([{ fileId: 'DOC-1', section: 3 }]); expect(changed.parameters.retained).toBe('keep'); expect(original.parameters.documentSections).toBeUndefined()
    await view.setProps({ node: changed }); await view.get('[aria-label="评审批次序号"]').setValue(2)
    expect((view.emitted('change')!.at(-1)![0] as WorkflowNode).parameters.documentBatchOrdinal).toBe('1')
    await view.get('button').trigger('click'); const empty = view.emitted('change')!.at(-1)![0] as WorkflowNode
    expect(empty.parameters.documentSections).toBe('[]'); await view.setProps({ node: empty }); expect(view.get('[role=alert]').text()).toContain('至少选择')
  })
  it('无法识别的配置不隐式丢失，替换需确认', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const view = mount(WorkflowDocumentReviewEditor, { props: { node: node({ documentSections: '{broken' }) } })
    await view.get('button').trigger('click'); expect(view.emitted('change')).toBeUndefined()
    confirm.mockReturnValue(true); await view.get('button').trigger('click'); expect((view.emitted('change')![0]![0] as WorkflowNode).parameters.documentSections).toBe('')
  })
  it('锁定画布不能修改批次', async () => {
    const view = mount(WorkflowDocumentReviewEditor, { props: { node: node(), disabled: true } })
    await view.get('input').setValue(3); await view.get('select').setValue('SELECTED'); expect(view.emitted('change')).toBeUndefined()
  })
  it.each(['document.direct-review', 'document.direct-review-check'])('专业交付固定，复核完成策略仍由用户选择 %s', moduleId => {
    const n = { ...node(), moduleId, outputs: [{ name: 'result', title: '评审结果', kind: 'JSON' as const, required: true }] }
    const view = mount(WorkflowNodeEditor, { props: { node: n, graph: { schemaVersion: 1, nodes: [n], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
    expect(view.get('[aria-label="专业交付物"]').text()).toContain('评审结果'); expect(view.text()).not.toContain('添加交付物'); expect(view.text()).not.toContain('添加业务结果')
    expect(view.find('option[value="DELIVERABLES"]').exists()).toBe(true); expect(view.find('option[value="OUTCOME"]').exists()).toBe(moduleId.endsWith('-check'))
  })
})
