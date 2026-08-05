import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StageRail from '@/components/StageRail.vue'
import type { Stage } from '@/types/domain'

describe('StageRail', () => {
  it('shows complete stage objectives in separate cards connected by a full circuit segment', () => {
    const stages: Stage[] = [
      { id: 'stage-1', ordinal: 1, objective: '实现完整功能，不截断任何阶段目标内容', status: 'SUCCEEDED', attempts: [{ id: 'a1' } as Stage['attempts'][number]] },
      { id: 'stage-2', ordinal: 2, objective: '运行确定性验证并核对最终交付证据', status: 'SUCCEEDED', attempts: [{ id: 'a2' } as Stage['attempts'][number]] },
    ]

    const wrapper = mount(StageRail, { props: { stages }, global: { stubs: { Icon: true } } })

    expect(wrapper.findAll('.phase-card')).toHaveLength(2)
    expect(wrapper.findAll('.phase-objective p').map((item) => item.text())).toEqual(stages.map((stage) => stage.objective))
    expect(wrapper.findAll('.stage-connector')).toHaveLength(1)
    expect(wrapper.get('.stage-connector').classes()).toContain('connector-complete')
    expect(wrapper.text()).toContain('PHASE 01')
    expect(wrapper.text()).toContain('1 次尝试')
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
  })
})
