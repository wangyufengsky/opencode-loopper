import { describe, expect, it } from 'vitest'
import { appendPreset, presetSources } from './presets'
import { preset, template } from './workflowTestFixtures'

describe('preset input binding', () => {
  it('copies the explicit role version and connects each upstream source only once', () => {
    const graph = template().graph, value = preset()
    value.inputs.push({ ...value.inputs[0]!, name: 'reference', title: '补充资料' })
    const before = JSON.stringify({ graph, value })
    const inserted = appendPreset(graph, value, { material: 'NODE|review|result', reference: 'NODE|review|result' })
    expect(inserted.node.roleRevisionId).toBe('role-v3')
    expect(inserted.node.id).not.toBe(value.node.id)
    expect(inserted.node.outputs).toEqual(value.node.outputs)
    expect(inserted.node.inputs).toEqual(['material', 'reference'].map(name => ({ name, source: 'NODE', sourceId: 'review', output: 'result', kind: 'TEXT', required: true })))
    expect(inserted.graph.edges).toEqual([expect.objectContaining({ from: 'review', to: inserted.node.id })])
    expect(JSON.stringify({ graph, value })).toBe(before)
  })
  it('binds public input without inventing a parent and permits unbound optional inputs', () => {
    const graph = template().graph
    graph.inputs = [{ name: 'brief', title: '需求资料', kind: 'TEXT', required: true }]
    const inserted = appendPreset(graph, preset(), { material: 'REQUIREMENT|brief|' })
    expect(inserted.node.inputs[0]).toEqual({ name: 'material', source: 'REQUIREMENT', sourceId: 'brief', output: null, kind: 'TEXT', required: true })
    expect(inserted.graph.edges).toEqual([])
    expect(appendPreset(graph, preset(), {}).node.inputs).toEqual([])
  })
  it('rejects disappeared, mismatched or missing required sources before changing the graph', () => {
    const graph = template().graph, value = preset()
    value.inputs[0]!.required = true
    expect(() => appendPreset(graph, value, {})).toThrow('请选择“参考资料”的输入来源')
    expect(() => appendPreset(graph, value, { material: 'NODE|removed|result' })).toThrow('来源已变化')
    graph.nodes[0]!.outputs[0]!.kind = 'CODE'
    expect(presetSources(graph, 'TEXT')).toEqual([])
    expect(() => appendPreset(graph, value, { material: 'NODE|review|result' })).toThrow('来源已变化')
    expect(graph.nodes).toHaveLength(1)
  })
})
