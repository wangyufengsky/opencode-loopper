import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import type { WorkflowNode } from '@/types/domain'
import { WorkflowCommandEditor as WorkflowCommandEditor } from '@/pages/w5/workflow/NodeSpecialists'
import { commandPreset } from './workflowTestFixtures'
afterEach(() => vi.restoreAllMocks())
function node(): WorkflowNode { const value = commandPreset().node; value.parameters.retained = 'keep'; value.inputs = [{ name: 'code', source: 'NODE', sourceId: 'parent', output: 'code', kind: 'CODE', required: true }]; return value }
describe('command verification editor', () => {
  it('preserves argument boundaries, exact output matching and unrelated parameters', async () => {
    const value = node(), wrapper = mount(WorkflowCommandEditor, { props: { node: value } })
    await wrapper.get('input[placeholder]').setValue('/path with spaces/check')
    let changed = wrapper.emitted('change')!.at(-1)![0] as WorkflowNode; await wrapper.setProps({ node: changed })
    await wrapper.findAll('button').find(button => button.attributes('data-semantic') === 'workflow.addParameter')!.trigger('click')
    changed = wrapper.emitted('change')!.at(-1)![0] as WorkflowNode; await wrapper.setProps({ node: changed })
    await wrapper.get('.workflow-binding input').setValue('name with spaces')
    changed = wrapper.emitted('change')!.at(-1)![0] as WorkflowNode; await wrapper.setProps({ node: changed })
    await wrapper.get('textarea').setValue('  expected\n')
    changed = wrapper.emitted('change')!.at(-1)![0] as WorkflowNode
    expect(JSON.parse(changed.parameters.commandVerification!)).toMatchObject({ argv: ['/path with spaces/check', 'name with spaces'], outputContains: '  expected\n' }); expect(changed.parameters.retained).toBe('keep')
    expect(JSON.parse(value.parameters.commandVerification!).argv).toEqual([]); wrapper.unmount()
  })
  it('retains unreadable configuration until the user confirms replacement', async () => {
     const value = node(); value.parameters.commandVerification = '{broken'
    const wrapper = mount(WorkflowCommandEditor, { props: { node: value } }); expect(wrapper.get('[role=alert]').text()).toContain('无法读取')
    await wrapper.get('button').trigger('click'); await resolveDialog(wrapper, false); expect(wrapper.emitted('change')).toBeUndefined()
    await wrapper.get('button').trigger('click'); await resolveDialog(wrapper, true)
    expect(JSON.parse((wrapper.emitted('change')![0]![0] as WorkflowNode).parameters.commandVerification!).argv).toEqual([]); wrapper.unmount()
  })
  it('keeps a removed input unresolved and blocks edits on a locked canvas', async () => {
    const value = node(); value.inputs = []; const wrapper = mount(WorkflowCommandEditor, { props: { node: value, disabled: true } })
    expect(wrapper.get('select option').text()).toContain('有效的上游代码输入'); await wrapper.get('input[placeholder]').setValue('changed')
    expect(wrapper.emitted('change')).toBeUndefined(); wrapper.unmount()
  })
  it.each([{ version: 2 }, { argv: [null] }, { purpose: 'FUTURE' }, { future: true }])('preserves unsupported configurations without implicit conversion', invalid => {
    const value = node(); value.parameters.commandVerification = JSON.stringify({ ...JSON.parse(value.parameters.commandVerification!), ...invalid })
    const wrapper = mount(WorkflowCommandEditor, { props: { node: value } }); expect(wrapper.get('[role=alert]').text()).toContain('无法读取'); expect(wrapper.emitted('change')).toBeUndefined(); wrapper.unmount()
  })
})
