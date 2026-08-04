import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInput } from 'element-plus'
import { describe, expect, it } from 'vitest'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'

const source = JSON.stringify({
  schemaVersion: 'v1', projectId: 'project-1', goal: '实现任务控制台', context: '只允许修改 src/**',
  stages: [{ objective: '实现并验证', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['可验证实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true }] }],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' },
}, null, 2)

describe('LoopSpecEditor', () => {
  it('shows JSON as Chinese structured fields with adaptive textareas', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source, ariaLabel: 'LoopSpec 表单' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })

    expect(wrapper.attributes('aria-label')).toBe('LoopSpec 表单')
    expect(wrapper.text()).toContain('任务目标与执行上下文')
    expect(wrapper.text()).toContain('允许修改路径')
    expect(wrapper.text()).toContain('验收器')
    expect(wrapper.find('.cm-editor').exists()).toBe(false)
    expect(wrapper.findAllComponents(ElInput).some((input) => Boolean(input.props('autosize')))).toBe(true)

    await wrapper.get('textarea[aria-label="任务目标"]').setValue('更新后的目标')
    await flushPromises()
    const emitted = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as string
    expect(JSON.parse(emitted).goal).toBe('更新后的目标')
  })

  it('follows external JSON updates without losing the structured view', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    const changed = JSON.stringify({ ...JSON.parse(source), goal: '第二个目标' }, null, 2)

    await wrapper.setProps({ modelValue: changed })
    await flushPromises()

    expect((wrapper.get('textarea[aria-label="任务目标"]').element as HTMLTextAreaElement).value).toBe('第二个目标')
  })
})
