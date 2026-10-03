import { useState } from 'react'
import type { WorkflowGraph, WorkflowOutput } from '@/types/domain'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'

export function WorkflowPublicInputs({ graph, disabled, onChange }: { graph: WorkflowGraph; disabled?: boolean; onChange(graph: WorkflowGraph): void }) {
  const [error, setError] = useState(''), [removing, setRemoving] = useState<WorkflowOutput | null>(null)
  const patch = (index: number, value: Partial<WorkflowOutput>) => {
    if (disabled) return
    const previous = graph.inputs[index]; if (!previous) return
    const next = { ...previous, ...value }; setError('')
    if (graph.inputs.some((input, i) => i !== index && input.name === next.name)) { setError('引用名称已被使用，请换一个名称。'); return }
    onChange({ ...graph, inputs: graph.inputs.map((input, i) => i === index ? next : input), nodes: graph.nodes.map(node => ({ ...node, inputs: node.inputs.map(input => input.source === 'REQUIREMENT' && input.sourceId === previous.name ? { ...input, sourceId: next.name, kind: next.kind } : input) })) })
  }
  const changedRemoval = !!removing && JSON.stringify(graph.inputs.find(item => item.name === removing.name)) !== JSON.stringify(removing)
  const referenced = (item: WorkflowOutput) => graph.nodes.some(node => node.inputs.some(input => input.source === 'REQUIREMENT' && input.sourceId === item.name))
  return <fieldset className="workflow-fields" disabled={disabled}><legend>公共资料设置</legend>{graph.inputs.map((item, index) => <div className="workflow-binding" key={index}><label>资料名称<input value={item.title} onChange={e => patch(index, { title: e.target.value })} /></label><label>引用名称<input value={item.name} onChange={e => patch(index, { name: e.target.value })} /></label><label>内容类型<select value={item.kind} onChange={e => patch(index, { kind: e.target.value as WorkflowOutput['kind'] })}><option value="TEXT">文本</option><option value="JSON">结构化数据</option><option value="DOCUMENT">上传文档</option></select></label><label><input type="checkbox" checked={item.required} onChange={e => patch(index, { required: e.target.checked })} />必需</label><UiActionButton actionKey="ui.delete" target={`公共资料 ${item.title}`} onAction={() => { if (referenced(item)) setError('公共资料仍被节点使用，请先调整节点输入。'); else { setError(''); setRemoving(item) } }} /></div>)}{error && <p role="alert">{error}</p>}
    <UiActionButton actionKey="workflow.addPublicInput" onAction={() => { if (disabled) return; let i = 1; while (graph.inputs.some(item => item.name === `input${i}`)) i++; onChange({ ...graph, inputs: [...graph.inputs, { name: `input${i}`, title: '公共资料', kind: 'TEXT', required: true }] }) }} />
    <UiConfirmDialog open={!!removing} title="移除公共资料声明" confirmActionKey="ui.delete" target={removing?.title} onCancel={() => setRemoving(null)} policy={disabled || changedRemoval || !!removing && referenced(removing) ? { kind: 'block', reason: disabled ? '当前流程只读。' : changedRemoval ? '声明已变化，请关闭后重新确认。' : '公共资料仍被节点使用，请先调整输入。' } : { kind: 'allow' }} onConfirm={() => { if (removing && !disabled && !changedRemoval && !referenced(removing)) { onChange({ ...graph, inputs: graph.inputs.filter(item => item.name !== removing.name) }); setRemoving(null) } }}>移除这项公共资料声明？</UiConfirmDialog>
  </fieldset>
}
