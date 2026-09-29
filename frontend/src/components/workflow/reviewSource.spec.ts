import { describe, expect, it } from 'vitest'
import type { WorkflowGraph, WorkflowNode } from '@/types/domain'
import { replaceReviewSource } from './reviewSource'
const node = (mode: 'FULL' | 'DATE_INCREMENTAL'): WorkflowNode => ({ id: 'source', title: '固定版本', kind: 'SYSTEM', moduleId: 'system.review.snapshot', moduleVersion: 1, roleId: null,
  task: '', inputs: (mode === 'FULL' ? ['branch'] : ['branch', 'startDate', 'endDate']).map(name => ({ name, source: 'REQUIREMENT', sourceId: name, kind: 'TEXT', required: true, output: null })),
  outputs: [], outcomes: [], completion: { kind: 'VERIFIED', criterion: '', expectedOutcome: null }, maxRetries: 0, pauseAfter: false, parameters: { reviewMode: mode } })
const graph = (mode: 'FULL' | 'DATE_INCREMENTAL'): WorkflowGraph => ({ schemaVersion: 1, nodes: [node(mode)], edges: [], inputs: node(mode).inputs.map(i => ({ name: i.name, title: i.name, kind: 'TEXT', required: true })) })
describe('审查范围切换的公共日期', () => {
  it('全面审查移除无人使用的日期，日期增量补齐公共字段且原图可撤销', () => {
    const original = graph('DATE_INCREMENTAL'), full = replaceReviewSource(original, node('FULL'))
    expect(full.inputs.map(i => i.name)).toEqual(['branch']); expect(original.inputs).toHaveLength(3)
    const dated = replaceReviewSource(full, node('DATE_INCREMENTAL')); expect(dated.inputs.map(i => i.name)).toEqual(['branch', 'startDate', 'endDate'])
    expect(dated.inputs[1]?.title).toContain('开始日期')
  })
  it('其他节点使用的日期及已固定需求的资料声明保留', () => {
    const original = graph('DATE_INCREMENTAL'); original.nodes.push({ ...node('DATE_INCREMENTAL'), id: 'other' })
    expect(replaceReviewSource(original, node('FULL')).inputs).toEqual(original.inputs)
    const frozen = graph('DATE_INCREMENTAL'); expect(replaceReviewSource(frozen, node('FULL'), false).inputs).toEqual(frozen.inputs)
  })
})
