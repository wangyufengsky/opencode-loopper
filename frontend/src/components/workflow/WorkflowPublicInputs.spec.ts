import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkflowPublicInputs from './WorkflowPublicInputs.vue'
import { requirement } from './workflowRunTestFixtures'
import type { WorkflowGraph } from '@/types/domain'
describe('public input editing', () => {
  it('updates a renamed source in all consumers and refuses removal while still bound', async () => {
    const graph = requirement().graph; graph.inputs = [{ name: 'source', title: '资料', kind: 'TEXT', required: true }]; graph.nodes[0]!.inputs = [{ name: 'evidence', source: 'REQUIREMENT', sourceId: 'source', output: null, kind: 'TEXT', required: true }]
    const wrapper = mount(WorkflowPublicInputs, { props: { graph } }); await wrapper.findAll('input')[1]!.setValue('renamed'); const next = wrapper.emitted('change')![0]![0] as WorkflowGraph; expect(next.nodes[0]!.inputs[0]!.sourceId).toBe('renamed')
    await wrapper.get('button').trigger('click'); expect(wrapper.text()).toContain('仍被节点使用'); expect(wrapper.emitted('change')).toHaveLength(1); wrapper.unmount()
  })
  it('allocates a free reference name after deletion instead of colliding with remaining sources', async () => {
    const graph = requirement().graph; graph.inputs = [{ name: 'input2', title: '资料', kind: 'TEXT', required: true }]; const wrapper = mount(WorkflowPublicInputs, { props: { graph } }); await wrapper.findAll('button').at(-1)!.trigger('click'); const next = wrapper.emitted('change')![0]![0] as WorkflowGraph; expect(next.inputs.map(value => value.name)).toEqual(['input2', 'input1']); wrapper.unmount()
  })
})
