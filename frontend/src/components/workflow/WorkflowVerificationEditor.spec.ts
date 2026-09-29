import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkflowVerificationEditor from './WorkflowVerificationEditor.vue'
import { preset } from './workflowTestFixtures'
import type { WorkflowNode } from '@/types/domain'
afterEach(() => vi.restoreAllMocks())
function node(): WorkflowNode { return { ...preset().node, kind: 'SYSTEM', roleId: null, roleRevisionId: null, moduleId: 'system.verify.files', parameters: { retained: 'keep', verification: JSON.stringify({ version: 1, inputName: 'code', checks: [{ title: '检查文件', type: 'FILE_CONTENT', path: 'a.txt', expected: 'old', matchMode: 'EXACT' }] }) } } }
describe('file verification configuration', () => {
  it('preserves exact whitespace and unrelated parameters while changing a criterion', async () => {
    const value = node(), wrapper = mount(WorkflowVerificationEditor, { props: { node: value } })
    await wrapper.get('textarea').setValue('  expected\n')
    const changed = wrapper.emitted('change')![0]![0] as WorkflowNode
    expect(JSON.parse(changed.parameters.verification!).checks[0].expected).toBe('  expected\n'); expect(changed.parameters.retained).toBe('keep')
    expect(JSON.parse(value.parameters.verification!).checks[0].expected).toBe('old'); wrapper.unmount()
  })
  it('keeps unreadable configuration until the user explicitly replaces it', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const value = node(); value.parameters.verification = '{broken'
    const wrapper = mount(WorkflowVerificationEditor, { props: { node: value } }); expect(wrapper.get('[role=alert]').text()).toContain('无法读取')
    await wrapper.get('button').trigger('click'); expect(wrapper.emitted('change')).toBeUndefined()
    vi.mocked(window.confirm).mockReturnValue(true); await wrapper.get('button').trigger('click'); expect(JSON.parse((wrapper.emitted('change')![0]![0] as WorkflowNode).parameters.verification!).checks).toEqual([]); wrapper.unmount()
  })
  it('requires confirmation to remove a check and blocks changes while the canvas is locked', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = mount(WorkflowVerificationEditor, { props: { node: node() } }); await wrapper.findAll('button').find(button => button.text() === '移除检查')!.trigger('click'); expect(wrapper.emitted('change')).toBeUndefined()
    await wrapper.setProps({ disabled: true }); await wrapper.get('input').setValue('changed'); expect(wrapper.emitted('change')).toBeUndefined(); wrapper.unmount()
  })
  it.each([null, { title: 'broken' }, { title: '未知检查', type: 'PROCESS', path: 'script' }])('preserves unsupported check shapes without crashing or silently converting them', check => {
    const value = node(); value.parameters.verification = JSON.stringify({ version: 1, inputName: 'code', checks: [check] })
    const wrapper = mount(WorkflowVerificationEditor, { props: { node: value } })
    expect(wrapper.get('[role=alert]').text()).toContain('无法读取'); expect(wrapper.emitted('change')).toBeUndefined(); wrapper.unmount()
  })
})
