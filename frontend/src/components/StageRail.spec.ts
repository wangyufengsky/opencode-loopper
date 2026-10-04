import { enableAutoUnmount, flushPromises, mount } from '@/pages/w6-tests/ordinary/render'
import { waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { StageDiagram as StageRail } from '@/react/diagrams/StageDiagram'
import type { Stage } from '@/types/domain'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'
enableAutoUnmount(afterEach)
beforeEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))
afterEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))

describe('StageRail', () => {
  it('shows the persisted attempt count before audit details have loaded', () => {
    const wrapper = mount(StageRail, { props: { stages: [{ id: 'document', ordinal: 1,
      objective: '撰写文档', status: 'SUCCEEDED', attemptCount: 1, attempts: [] }] },
      global: { stubs: { Icon: true } } })
    expect(wrapper.text()).toContain('1 次尝试')
    expect(wrapper.text()).not.toContain('尚未尝试')
  })
  it('shows complete stage objectives in separate cards connected by a full circuit segment', async () => {
    const stages: Stage[] = [
      { id: 'stage-1', ordinal: 1, objective: '实现完整功能，不截断任何阶段目标内容', status: 'SUCCEEDED', attempts: [{ id: 'a1' } as Stage['attempts'][number]] },
      { id: 'stage-2', ordinal: 2, objective: '运行确定性验证并核对最终交付证据', status: 'SUCCEEDED', attempts: [{ id: 'a2' } as Stage['attempts'][number]] },
    ]

    const wrapper = mount(StageRail, { props: { stages }, global: { stubs: { Icon: true } } })
    await flushPromises()
    await waitFor(() => expect(wrapper.findAll('.stage-connector')).toHaveLength(1))

    expect(wrapper.findAll('.phase-card')).toHaveLength(2)
    expect(wrapper.findAll('.phase-objective p').map((item) => item.text())).toEqual(stages.map((stage) => stage.objective))
    expect(wrapper.findAll('.stage-connector')).toHaveLength(1)
    expect(wrapper.get('.stage-connector').classes()).toContain('connector-complete')
    expect(wrapper.text()).toContain('阶段 01')
    expect(wrapper.text()).toContain('1 次尝试')
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
  })
  it('uses React by default and preserves the Vue adapter props after a server status update', async () => {
    const stage: Stage = { id: 'stage', ordinal: 1, objective: '真实阶段', status: 'RUNNING', attempts: [] }
    const wrapper = mount(StageRail, { props: { stages: [stage] } })
    expect(wrapper.find('[data-canvas-runtime="react"] .react-flow').exists()).toBe(true)
    await wrapper.setProps({ stages: [{ ...stage, status: 'SUCCEEDED', attemptCount: 2 }] })
    expect(wrapper.get('.phase-card').classes()).toContain('is-succeeded')
    expect(wrapper.text()).toContain('2 次尝试')
  })
  it('keeps a mounted runtime stable and applies the Vue rollback only to a new instance', () => {
    const stages: Stage[] = [{ id: 'stage', ordinal: 1, objective: '冻结阶段目标', status: 'PAUSED', attemptCount: 2, attempts: [] }]
    const current = mount(StageRail, { props: { stages } })
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ documents: 'vue', tasks: 'vue' }))
    expect(current.find('.react-flow').exists()).toBe(true)
    const next = mount(StageRail, { props: { stages } })
    expect(next.find('[data-canvas-runtime="react"][data-canvas-kind="stages"]').exists()).toBe(true)
    expect(next.find('.react-flow').exists()).toBe(true)
    expect(next.text()).toContain('冻结阶段目标')
    expect(next.text()).toContain('2 次尝试')
  })
})
