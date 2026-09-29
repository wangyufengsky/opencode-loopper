import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkflowCanvas from './WorkflowCanvas.vue'
import { template } from './workflowTestFixtures'
describe('workflow canvas interactions', () => {
  it('supports keyboard move/connect/delete and leaves dependencies unchanged', async () => {
    const draft = template(), wrapper = mount(WorkflowCanvas, { props: { graph: draft.graph, layout: draft.layout }, global: { stubs: { Icon: true } } })
    const node = wrapper.get('article'); await node.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('layout')?.[0]?.[0]).toMatchObject({ positions: { review: { x: 24, y: 0 } } }); expect(draft.graph.edges).toEqual([])
    await wrapper.setProps({ connecting: 'other' }); await node.trigger('keydown', { key: 'Enter' }); expect(wrapper.emitted('connect')).toEqual([['review']])
    await node.trigger('keydown', { key: 'Delete' }); expect(wrapper.emitted('remove')).toEqual([['review']]); wrapper.unmount()
  })
  it('prevents keyboard edits of built-in nodes while allowing zoom', async () => {
    const draft = template(), wrapper = mount(WorkflowCanvas, { props: { graph: draft.graph, layout: draft.layout, readonly: true }, global: { stubs: { Icon: true } } })
    await wrapper.get('article').trigger('keydown', { key: 'ArrowRight' }); await wrapper.get('article').trigger('keydown', { key: 'Delete' }); expect(wrapper.emitted('remove')).toBeUndefined(); expect(wrapper.emitted('layout')).toBeUndefined()
    await wrapper.get('[aria-label="放大画布"]').trigger('click'); expect(wrapper.emitted('layout')?.[0]?.[0]).toMatchObject({ zoom: 1.2 }); wrapper.unmount()
  })
})
