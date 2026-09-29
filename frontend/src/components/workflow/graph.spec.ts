import { describe, expect, it } from 'vitest'
import { autoLayout, connect, emptyGraph, newNode, removeNode, outcomeTitle } from './graph'
function graph() { return { ...emptyGraph(), nodes: ['设计', '开发', '验收'].map(title => ({ ...newNode('human'), id: title, title })) } }
describe('canvas dependency edits', () => {
  it('keeps Chinese result labels distinct from stable condition keys, including long keys', () => {
    const node = newNode('human'), key = 'r'.repeat(64); node.outcomes = [key]; node.parameters.outcomeTitles = JSON.stringify({ [key]: '需要修复' })
    expect(outcomeTitle(node, key)).toBe('需要修复'); expect(node.outcomes).toEqual([key]); node.parameters.outcomeTitles = 'malformed'; expect(outcomeTitle(node, key)).toBe('业务结果 1')
    node.outcomes = ['constructor']; expect(outcomeTitle(node, 'constructor')).toBe('业务结果 1')
  })
  it('rejects a cycle and duplicate conditional edge without changing the original graph', () => {
    const g = connect(connect(graph(), '设计', '开发'), '开发', '验收')
    expect(() => connect(g, '验收', '设计')).toThrow('循环'); g.edges[0]!.outcome = 'pass'
    expect(() => connect(g, '设计', '开发')).toThrow('已经连接'); expect(g.edges).toHaveLength(2)
  })
  it('keeps consumers safe when removing a producer and removes incident edges otherwise', () => {
    const g = connect(graph(), '设计', '开发'); g.nodes[1]!.inputs = [{ name: 'design', source: 'NODE', sourceId: '设计', output: 'result', kind: 'TEXT', required: true }]
    expect(() => removeNode(g, '设计')).toThrow('开发'); g.nodes[1]!.inputs = []
    const result = removeNode(g, '设计'); expect(result.nodes).toHaveLength(2); expect(result.edges).toHaveLength(0); expect(g.nodes).toHaveLength(3)
  })
  it('lays out a parallel join after both parents', () => {
    const g = connect(connect(graph(), '设计', '验收'), '开发', '验收'), points = autoLayout(g)
    expect(points['设计']!.y).toBe(points['开发']!.y); expect(points['验收']!.y).toBeGreaterThan(points['开发']!.y)
  })
})
