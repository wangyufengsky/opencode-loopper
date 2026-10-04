import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { installPointerEnvironment, type PointerEnvironment } from '@/react/workflow/workflowPointerTestHelpers'
import { WorkflowCanvasView as WorkflowCanvas } from '@/react/workflow/WorkflowCanvasReact'
import type { WorkflowCanvasHandle } from '@/react/workflow/types'
import { template } from './workflowTestFixtures'
describe.each(['vue', 'react'] as const)('%s workflow canvas interactions', runtime => {
  let input: PointerEnvironment
  beforeEach(() => { localStorage.setItem('loopper.canvas-runtime', JSON.stringify({ workflows: runtime })); input = installPointerEnvironment() })
  afterEach(() => input.restore())
  it('supports keyboard move/connect/delete and leaves dependencies unchanged', async () => {
    const draft = template(), wrapper = mount(WorkflowCanvas, { props: { graph: draft.graph, layout: draft.layout } })
    const node = wrapper.get('article'); await node.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('layout')?.[0]?.[0]).toMatchObject({ positions: { review: { x: 24, y: 0 } } }); expect(draft.graph.edges).toEqual([])
    await wrapper.setProps({ connecting: 'other' }); await node.trigger('keydown', { key: 'Enter' }); expect(wrapper.emitted('connect')).toEqual([['review']])
    await node.trigger('keydown', { key: 'Delete' }); expect(wrapper.emitted('remove')).toEqual([['review']]); wrapper.unmount()
  })
  it('prevents keyboard edits of built-in nodes while allowing zoom', async () => {
    const draft = template(), wrapper = mount(WorkflowCanvas, { props: { graph: draft.graph, layout: draft.layout, readonly: true } })
    await wrapper.get('article').trigger('keydown', { key: 'ArrowRight' }); await wrapper.get('article').trigger('keydown', { key: 'Delete' }); expect(wrapper.emitted('remove')).toBeUndefined(); expect(wrapper.emitted('layout')).toBeUndefined()
    await wrapper.get('[aria-label="放大画布"]').trigger('click'); expect(wrapper.emitted('layout')?.[0]?.[0]).toMatchObject({ zoom: 1.2 }); wrapper.unmount()
  })
  it('cancels only on a blank click or Escape, not controls, node clicks or panning', async () => {
    const value = template(), view = mount(WorkflowCanvas, { props: { graph: value.graph, layout: value.layout } })
    await view.get('article').trigger('click'); await view.get('[aria-label="放大画布"]').trigger('click')
    expect(view.emitted('cancel')).toBeUndefined()
    const surface = view.get<HTMLElement>('.workflow-canvas').element
    input.pointer(surface, 'pointerdown', 20, 20)
    input.pointer(surface, 'pointermove', 60, 70)
    input.pointer(surface, 'pointerup', 60, 70)
    await view.get('.workflow-canvas').trigger('click')
    expect(view.emitted('cancel')).toBeUndefined()
    await view.get('.workflow-canvas').trigger('click'); await view.get('article').trigger('keydown', { key: 'Escape' })
    expect(view.emitted('cancel')).toHaveLength(2); view.unmount()
  })
  it('reveals connection controls only for the selected node and connects without selecting the target first', async () => {
    const value = template(), view = mount(WorkflowCanvas, { props: { graph: value.graph, layout: value.layout } })
    expect(view.find('.workflow-port').exists()).toBe(false)
    await view.setProps({ selected: 'review' }); expect(view.find('.workflow-port').exists()).toBe(true)
    await view.setProps({ connecting: 'other' })
    const article = view.get<HTMLElement>('article').element
    input.pointer(article, 'pointerdown', 20, 20)
    input.pointer(article, 'pointerup', 20, 20)
    await view.get('article').trigger('click')
    expect(view.emitted('select')).toBeUndefined(); expect(view.emitted('connect')).toEqual([['review']]); view.unmount()
  })
  it('makes connections selectable with Space and locates nodes without emitting a saved layout', async () => {
    const value = template(); value.graph.nodes.push({ ...value.graph.nodes[0]!, id: 'later', title: '后续' }); value.graph.edges = [{ id: 'edge', from: 'review', to: 'later', outcome: null }]
    let handle: WorkflowCanvasHandle | undefined
    const view = mount(WorkflowCanvas, { props: { graph: value.graph, layout: value.layout, selectedEdge: 'edge', onReady: value => { handle = value } } })
    await vi.waitFor(() => expect(view.find('.workflow-wire-hit').exists()).toBe(true))
    const wire = view.get('.workflow-wire-hit'); expect(wire.attributes('aria-pressed')).toBe('true')
    await wire.trigger('keydown', { key: ' ' }); expect(view.emitted('edge')).toEqual([['edge']])
    handle!.reveal('later'); expect(view.emitted('layout')).toBeUndefined(); view.unmount()
  })

})
