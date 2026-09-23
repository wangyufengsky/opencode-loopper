import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElCheckbox } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { SourceTemplateOverview } from '@/types/domain'
import SourceTemplateView from './SourceTemplateView.vue'

function fixture(overrides: Partial<SourceTemplateOverview> = {}): SourceTemplateOverview {
  return { id: 's1', projectId: 'p1', templateId: 'UNIT_TEST_DEVELOPMENT', templateVersion: '1', title: '单元测试开发',
    state: 'PENDING_START', version: 0, createdAt: 'now', updatedAt: 'now', archived: false, sourcePath: 'src/main',
    testOutputPath: null, documentPath: null, requirements: '', snapshot: null, designerId: null, taskId: null, taskState: null,
    waitingReasonCode: null, waitingMessage: null, coverage: [], progress: [], canResume: false, canArchive: false, testProfile: null, ...overrides }
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function render() {
  vi.stubGlobal('EventSource', undefined)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/template-tasks/source-runs/:id', component: { template: '<div />' } }] })
  await router.push('/template-tasks/source-runs/s1'); await router.isReady()
  const wrapper = mount(SourceTemplateView, { global: { plugins: [router, ElButton, ElCheckbox],
    stubs: { PageHeader: true, SourceCoveragePanel: true, SourceArtifactsPanel: true, StatusBadge: true } } })
  return { wrapper, router }
}
it('waits for formal start and reuses a lost-response command identity', async () => {
  vi.spyOn(api, 'sourceTemplate').mockResolvedValue(fixture())
  const command = vi.spyOn(api, 'sourceTemplateCommand').mockRejectedValueOnce(new Error('响应中断')).mockResolvedValue(fixture({ state: 'DESIGNING', version: 1 }))
  const { wrapper } = await render(); await flushPromises()
  expect(command).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('尚未调用模型')
  const start = () => wrapper.findAll('button').find(button => button.text() === '开始执行')!
  await start().trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('响应中断')
  await start().trigger('click'); await flushPromises()
  expect(command.mock.calls[1]![2]).toEqual(command.mock.calls[0]![2])
  expect(wrapper.text()).toContain('设计测试场景')
  expect(wrapper.findAll('button').some(button => button.text() === '开始执行')).toBe(false)
  wrapper.unmount()
})
it('keeps unknown stop blocked and shows the linked execution disposition independently', async () => {
  vi.spyOn(api, 'sourceTemplate').mockResolvedValue(fixture({ state: 'STOPPING', taskId: 't1', taskState: 'AWAITING_DECISION',
    waitingMessage: '停止尚未确认，目录租约继续保留', canResume: false }))
  const { wrapper } = await render(); await flushPromises()
  expect(wrapper.text()).toContain('停止尚未确认'); expect(wrapper.text()).toContain('结果仍待处置')
  expect(wrapper.findAll('button').some(button => button.text() === '从冻结输入恢复')).toBe(false)
  expect(wrapper.find('a[href="/tasks/t1"]').exists()).toBe(true)
  wrapper.unmount()
})
it('ignores old overview responses after changing the source run', async () => {
  let finish!: (value: SourceTemplateOverview) => void
  vi.spyOn(api, 'sourceTemplate').mockImplementationOnce(() => new Promise(done => { finish = done })).mockResolvedValue(fixture({ id: 's2', title: '第二次任务', state: 'COMPLETED' }))
  const { wrapper, router } = await render()
  await router.push('/template-tasks/source-runs/s2'); await flushPromises()
  finish(fixture({ state: 'WAITING_INPUT', waitingMessage: '旧任务问题', canResume: true })); await flushPromises()
  expect(wrapper.text()).not.toContain('旧任务问题')
  expect(wrapper.text()).toContain('已完成')
  wrapper.unmount()
})
