import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import type { ErrorEvent, JudgeRun } from '@/types/domain'

const error: ErrorEvent = {
  id: 'judge-conflict',
  layer: 'VERIFICATION',
  code: 'JUDGE_CONFLICT',
  message: 'Requirement Judge=PASS: very long raw text | Risk Judge=BLOCKED: very long raw text',
  retryable: false,
  occurredAt: '2026-08-10T02:51:22Z',
}

const judges: JudgeRun[] = [
  {
    id: 'requirement-1',
    role: 'REQUIREMENT',
    ordinal: 1,
    status: 'COMPLETED',
    verdict: 'PASS',
    reason: '交付结果满足目标。\n## 证据\n1. 完整证据保留在评审记录中。',
    createdAt: '2026-08-10T02:50:00Z',
  },
  {
    id: 'risk-1',
    role: 'RISK',
    ordinal: 1,
    status: 'COMPLETED',
    verdict: 'BLOCKED',
    reason: '仍缺少安全边界证据。',
    createdAt: '2026-08-10T02:50:00Z',
  },
]

describe('LayeredErrorPanel', () => {
  it('renders judge conflicts as a compact structured review summary', () => {
    const wrapper = mount(LayeredErrorPanel, {
      props: { error, judges },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.get('.judge-attention-header h3').text()).toBe('需求 / 风险双评审尚未达成一致')
    expect(wrapper.findAll('.judge-attention-review')).toHaveLength(2)
    expect(wrapper.text()).toContain('需求评审')
    expect(wrapper.text()).toContain('风险评审')
    expect(wrapper.text()).toContain('交付结果满足目标。')
    expect(wrapper.text()).toContain('仍缺少安全边界证据。')
    expect(wrapper.text()).not.toContain('very long raw text')
  })

  it('keeps the existing layered presentation for non-judge verification errors', () => {
    const wrapper = mount(LayeredErrorPanel, {
      props: { error: { ...error, code: 'PROCESS_FAILED', message: '命令退出码为 1' } },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.find('.judge-attention-panel').exists()).toBe(false)
    expect(wrapper.get('.error-panel-verification').text()).toContain('验证未通过')
    expect(wrapper.text()).toContain('命令退出码为 1')
  })
})
