import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type ReactTestRoot } from '@/pages/w6-tests/workflow/react-test-root'
import { api } from '@/api/client'
import type { RoleDetail, RoleRevision } from '@/types/domain'
import { WorkflowRolePicker as WorkflowRolePicker } from '@/pages/w5/workflow/WorkflowRolePicker'
import { newNode } from './graph'
vi.mock('@/api/client', () => ({ api: { getRoles: vi.fn(), getRole: vi.fn(), getRoleRevision: vi.fn(), getRoleRevisions: vi.fn() } }))
const calls = vi.mocked(api)
const role: RoleDetail = { roleId: 'designer', displayName: '设计师', description: '', origin: 'BUILTIN', latestRevisionId: 'published', latestRevisionNumber: 2, activeSlots: [] }
const revision = (id = 'published', number = 2): RoleRevision => ({ roleId: role.roleId, revisionId: id, revisionNumber: number, contentSha256: 'hash', promptFragments: {}, manifest: { allowedSlots: ['WORKFLOW_READ_ONLY'], workInstructions: '分析授权资料' } })
let wrapper: ReactTestRoot | undefined
beforeEach(() => { vi.resetAllMocks(); calls.getRoles.mockResolvedValue({ items: [role] }); calls.getRole.mockResolvedValue(role); calls.getRoleRevision.mockResolvedValue(revision()) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
describe('frozen workflow role selection', () => {
  it.each(['free.write', 'source.test-write'])('emits the explicitly selected revision and refuses incompatible roles for %s', async moduleId => {
    wrapper = mount(WorkflowRolePicker, { props: { node: { ...newNode('free.write'), moduleId } } }); await flushPromises(); await wrapper.get('select').setValue(role.roleId); await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined(); expect(wrapper.get('[role="alert"]').text()).toContain('不支持当前工作类型')
    await wrapper.setProps({ node: newNode('free.readonly') }); await wrapper.get('select').setValue(role.roleId); await flushPromises(); expect(wrapper.emitted('change')).toEqual([[role.roleId, 'published']])
  })
  it('ignores a late label response from a previous frozen revision of the same node', async () => {
    let resolveOld: (value: RoleRevision) => void = () => {}
    calls.getRoleRevision.mockImplementation((_role, id) => id === 'old' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve(revision()))
    const node = { ...newNode('free.readonly'), roleId: role.roleId, roleRevisionId: 'old' }
    wrapper = mount(WorkflowRolePicker, { props: { node } }); await flushPromises(); await wrapper.setProps({ node: { ...node, roleRevisionId: 'published' } }); await flushPromises(); resolveOld(revision('old', 1)); await flushPromises()
    expect(wrapper.text()).toContain('设计师 · v2'); expect(wrapper.text()).not.toContain('v1'); expect(wrapper.emitted('change')).toBeUndefined()
  })
})
