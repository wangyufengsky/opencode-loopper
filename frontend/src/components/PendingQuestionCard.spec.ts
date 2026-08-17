import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import type { TaskSessionPendingQuestion } from '@/types/domain'

const pending: TaskSessionPendingQuestion = {
  id: 'question-1',
  questions: [
    {
      question: '选择实现范围', header: '范围', multiple: false, custom: false,
      options: [{ label: '新增链路', description: '创建新的业务责任链' }, { label: '只补测试', description: '不改生产代码' }],
    },
    {
      question: '选择业务域', header: '业务域', multiple: false, custom: true,
      options: [{ label: 'XML', description: '沿用现有领域' }],
    },
  ],
}

describe('PendingQuestionCard', () => {
  it('collects every answer and emits the OpenCode answer matrix', async () => {
    const wrapper = mount(PendingQuestionCard, { props: { pending }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })

    await wrapper.findAll('input[type="radio"]')[0]!.setValue(true)
    await wrapper.get('.designer-custom-answer textarea').setValue('由 Designer 决定')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('提交回答并继续'))!.trigger('click')

    expect(wrapper.emitted('submit')).toEqual([[[['新增链路'], ['由 Designer 决定']]]])
  })

  it('submits every recommended answer with one click and hides reject for mandatory design questions', async () => {
    const recommended: TaskSessionPendingQuestion = {
      id: 'question-recommended', scope: 'WP-2', discussionRevision: 4,
      questions: [
        { question: '选择兼容策略', header: '兼容', multiple: false, custom: false,
          options: [{ label: '全部重写', description: '扩大改动' }, { label: '保持兼容（推荐）', description: '延续现有合同' }] },
        { question: '选择验收', header: '验收', multiple: true, custom: true,
          options: [{ label: '聚焦测试 (Recommended)', description: '验证当前业务行为' }, { label: '只做构建', description: '不覆盖行为' }] },
      ],
    }
    const wrapper = mount(PendingQuestionCard, {
      props: { pending: recommended, mandatory: true },
      global: { plugins: [ElementPlus], stubs: { Icon: true } },
    })

    expect(wrapper.findAll('button').some((button) => button.text() === '拒绝')).toBe(false)
    await wrapper.findAll('button').find((button) => button.text().includes('采用全部推荐项'))!.trigger('click')

    expect(wrapper.emitted('submit')).toEqual([[[['保持兼容（推荐）'], ['聚焦测试 (Recommended)']]]])
  })
})
