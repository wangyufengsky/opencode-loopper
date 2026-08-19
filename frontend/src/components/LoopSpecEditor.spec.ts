import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInput, ElInputNumber, ElOption } from 'element-plus'
import { describe, expect, it } from 'vitest'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'

const source = JSON.stringify({
  schemaVersion: 'v1', projectId: 'project-1', goal: '实现任务控制台', context: '只允许修改 src/**',
  stages: [{ objective: '实现并验证', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['可验证实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true }] }],
  limits: { maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 4, stagnationLimit: 5, maxDuration: 'PT2H', attemptTimeout: 'PT30M', verifierTimeout: 'PT7M' },
  model: { providerId: 'provider-1', modelId: 'model-1', thinking: true },
  sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
  nextAttemptPromptTemplate: '处理 ${failureSummary}',
}, null, 2)

describe('LoopSpecEditor', () => {
  it('shows JSON as Chinese structured fields with adaptive textareas', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source, ariaLabel: 'LoopSpec 表单' }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })

    expect(wrapper.attributes('aria-label')).toBe('LoopSpec 表单')
    expect(wrapper.text()).toContain('任务目标与执行上下文')
    expect(wrapper.text()).toContain('建议修改路径')
    expect(wrapper.text()).toContain('验收器')
    expect(wrapper.find('.cm-editor').exists()).toBe(false)
    expect(wrapper.findAllComponents(ElInput).some((input) => Boolean(input.props('autosize')))).toBe(true)

    await wrapper.get('textarea[aria-label="任务目标"]').setValue('更新后的目标')
    await flushPromises()
    const emitted = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as string
    expect(JSON.parse(emitted)).toMatchObject({
      goal: '更新后的目标',
      limits: { sessionErrorLimit: 4, stagnationLimit: 5, verifierTimeout: 'PT7M' },
      model: { providerId: 'provider-1', modelId: 'model-1', thinking: true },
      sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
      nextAttemptPromptTemplate: '处理 ${failureSummary}',
    })
  })

  it('orders the review cards by the user workflow', () => {
    const wrapper = mount(LoopSpecEditor, {
      props: { modelValue: source },
      slots: { 'after-stages': '<section class="acceptance-marker">实际执行验收</section>' },
      global: { plugins: [ElementPlus], stubs: { Icon: true } },
    })
    const cards = wrapper.find('.loop-spec-form').element.children

    expect(Array.from(cards).map((card) => card.className)).toEqual([
      'form-section overview-section',
      'stages-section',
      'acceptance-marker',
      'form-section limits-section',
    ])
  })

  it('edits the fresh-session policy and next-attempt template without losing the threshold', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })

    await wrapper.get('textarea[aria-label="下一轮提示模板"]').setValue('下一轮先复核 ${changedPaths}')
    const freshSessionSwitch = wrapper.find('[aria-label="验证失败后自动新建会话"]')
    expect(freshSessionSwitch.exists()).toBe(true)
    await freshSessionSwitch.trigger('click')
    await flushPromises()

    const emitted = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as string
    expect(JSON.parse(emitted)).toMatchObject({
      limits: { stagnationLimit: 5 },
      sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: true },
      nextAttemptPromptTemplate: '下一轮先复核 ${changedPaths}',
    })
  })

  it('does not add path rules or a Git diff verifier to a new stage by default', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    const addStage = wrapper.findAll('button').find((button) => button.text().includes('添加阶段'))

    await addStage!.trigger('click')
    await flushPromises()

    const emitted = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as string
    expect(JSON.parse(emitted).stages[1]).toMatchObject({ allowedPaths: [], forbiddenPaths: [], verifiers: [] })
  })

  it('offers every native verifier type without accepting unknown free text', () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    const verifierOptions = wrapper.findAllComponents(ElOption).map((option) => option.props('value'))

    expect(verifierOptions).toEqual(expect.arrayContaining([
      'PROCESS', 'HTTP_STATUS', 'JSON_PATH', 'BROWSER', 'DATABASE_QUERY', 'FILE_CONTENT', 'FILE_HASH',
      'JUNIT_XML', 'GIT_DIFF', 'FILE_NOT_EXISTS', 'FILE_EXISTS',
    ]))
  })

  it('edits v2 criteria mappings and managed-runtime fields', async () => {
    const v2 = JSON.stringify({
      ...JSON.parse(source), schemaVersion: 'v2',
      stages: [{
        objective: 'HTTP behavior', allowedPaths: [], forbiddenPaths: [], deliverables: ['API'],
        acceptanceCriteria: [{ id: 'AC-1', description: 'health is UP', verificationMode: 'BOTH', judgeRubric: 'review health semantics' }],
        verificationRuntime: {
          startCommand: ['java', '-jar', 'app.jar', '--server.port={{LOOPPER_PORT}}'],
          readiness: { path: '/health', expectedStatus: 200, jsonPath: '$.status', expectedValue: 'UP', matchMode: 'EXACT' },
          startupTimeoutSeconds: 30, shutdownTimeoutSeconds: 5,
        },
        verifiers: [{ type: 'JSON_PATH', url: 'http://127.0.0.1:{{LOOPPER_PORT}}/health', httpMethod: 'GET', jsonPath: '$.status', expectedValue: 'UP', matchMode: 'EXACT', criterionIds: ['AC-1'] }],
      }],
    }, null, 2)
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: v2 }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })

    expect(wrapper.text()).toContain('行为验收条件')
    expect(wrapper.text()).toContain('分别规划机器验证、AI Judge 评审')
    expect(wrapper.text()).toContain('AI 评审准则')
    expect(wrapper.text()).toContain('托管临时运行时')
    expect(wrapper.text()).toContain('覆盖的验收条件')
    expect(wrapper.text()).toContain('JSON 匹配方式')
    expect(wrapper.text()).toContain('启动超时（秒）')
    const numberInputs = wrapper.findAllComponents(ElInputNumber)
    expect(numberInputs.find((input) => input.attributes('data-testid') === 'runtime-startup-timeout')?.props('max')).toBe(300)
    expect(numberInputs.find((input) => input.attributes('data-testid') === 'runtime-shutdown-timeout')?.props('max')).toBe(60)
    expect(numberInputs.find((input) => input.attributes('data-testid') === 'max-stage-attempts')?.props('max')).toBe(20)
  })

  it('follows external JSON updates without losing the structured view', async () => {
    const wrapper = mount(LoopSpecEditor, { props: { modelValue: source }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    const changed = JSON.stringify({ ...JSON.parse(source), goal: '第二个目标' }, null, 2)

    await wrapper.setProps({ modelValue: changed })
    await flushPromises()

    expect((wrapper.get('textarea[aria-label="任务目标"]').element as HTMLTextAreaElement).value).toBe('第二个目标')
  })
})
