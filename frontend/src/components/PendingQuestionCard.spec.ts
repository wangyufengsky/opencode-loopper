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
})
