import { enableAutoUnmount, mount } from '@/pages/w6-tests/workflow/react-test-root'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'
import type { RoleSlotBinding } from '@/types/domain'
import { RoleDiagram as RoleWorkflowDiagram } from '@/react/diagrams/RoleDiagram'
enableAutoUnmount(afterEach)
beforeEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))
afterEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))
const binding: RoleSlotBinding = { slot: 'PACKAGE_DESIGNER', profile: 'DEFAULT', activeRoleId: 'role', activeRevisionId: 'bound', bindingVersion: 1, label: '任务设计' }

describe('角色流程适配器', () => {
  it('默认 React 流程图仍发送已有绑定版本查看事件', async () => {
    const wrapper = mount(RoleWorkflowDiagram, { props: { bindings: [binding], latestRevisionId: 'newer' } })
    expect(wrapper.find('[data-canvas-kind="roles"] .react-flow').exists()).toBe(true)
    expect(wrapper.get('.workflow-track .current').text()).toContain('细化阶段与验收')
    await wrapper.get('.inline-button').trigger('click')
    expect(wrapper.emitted('revision')).toEqual([['bound']])
  })
  it('Vue 回退保持绑定版本和中文步骤', async () => {
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ documents: 'vue', roles: 'vue' }))
    const wrapper = mount(RoleWorkflowDiagram, { props: { bindings: [binding], latestRevisionId: 'bound' } })
    expect(wrapper.find('[data-canvas-runtime="react"]').exists()).toBe(true)
    expect(wrapper.find('.react-flow').exists()).toBe(true)
    expect(wrapper.text()).toContain('使用最新发布版本')
    await wrapper.get('.inline-button').trigger('click')
    expect(wrapper.emitted('revision')).toEqual([['bound']])
  })
})
