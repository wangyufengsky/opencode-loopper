import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JudgeReviewCard from '@/components/JudgeReviewCard.vue'
import type { JudgeRun } from '@/types/domain'

const judge: JudgeRun = {
  id: 'judge-1',
  role: 'REQUIREMENT',
  ordinal: 1,
  status: 'COMPLETED',
  verdict: 'PASS',
  reason: 'All objectives met. (1) `javac` exited 0. (2) The program printed **PASS**.',
  externalSessionId: 'ses_read_only_123',
  createdAt: '2026-08-05T00:00:00Z',
}

describe('JudgeReviewCard', () => {
  it('renders legacy numbered prose as readable, sanitized Markdown', () => {
    const wrapper = mount(JudgeReviewCard, {
      props: { judge },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.get('.judge-role strong').text()).toBe('需求评审员')
    expect(wrapper.get('.judge-verdict').text()).toContain('通过')
    expect(wrapper.get('.markdown-document > p').text()).toBe('All objectives met.')
    expect(wrapper.findAll('.markdown-document ol > li')).toHaveLength(2)
    expect(wrapper.get('.markdown-document code').text()).toBe('javac')
    expect(wrapper.get('.markdown-document strong').text()).toBe('PASS')
    expect(wrapper.text()).not.toContain('ses_read_only_123')
  })

  it('keeps newly generated structured Markdown intact', () => {
    const wrapper = mount(JudgeReviewCard, {
      props: {
        judge: {
          ...judge,
          reason: 'Delivery is safe.\n\n## Evidence\n\n1. Tests passed.\n2. Diff is scoped.',
        },
      },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.get('h2').text()).toBe('Evidence')
    expect(wrapper.findAll('ol > li').map((item) => item.text())).toEqual(['Tests passed.', 'Diff is scoped.'])
  })

  it('renders historical timeout as terminal evidence instead of waiting progress', () => {
    const wrapper = mount(JudgeReviewCard, {
      props: { judge: { ...judge, status: 'TIMED_OUT', verdict: undefined, reason: undefined } },
      global: { stubs: { Icon: true } },
    })

    expect(wrapper.get('.judge-verdict').text()).toContain('已超时')
    expect(wrapper.get('.markdown-document').text()).toContain('不再处于运行状态')
    expect(wrapper.text()).not.toContain('等待独立审阅结果')
  })
})
