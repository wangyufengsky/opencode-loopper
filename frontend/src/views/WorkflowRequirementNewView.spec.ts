import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { template } from '@/components/workflow/workflowTestFixtures'
import WorkflowRequirementNewView from './WorkflowRequirementNewView.vue'
vi.mock('@/api/workflow', () => ({ workflowApi: { get: vi.fn() } }))
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { create: vi.fn(), project: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: VueWrapper | undefined
beforeEach(() => { sessionStorage.clear(); vi.resetAllMocks(); vi.mocked(workflowApi.get).mockResolvedValue(template({ id: 'builtin.workflow.development', title: '默认开发流程', revision: 3 })); vi.spyOn(window, 'confirm').mockReturnValue(true) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render(query = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/requirements/new', component: WorkflowRequirementNewView }, { path: '/requirements/:id', component: { template: '<div>需求画布</div>' } }, { path: '/requirements', component: { template: '<div />' } }] })
  await router.push('/requirements/new' + query); await router.isReady(); wrapper = mount(RouterView, { global: { plugins: [router], stubs: { WorkflowChoice: { props: ['kind'], template: `<button type="button" @click="$emit('select', { id: kind, title: kind, revision: 2 })">{{ kind }}</button>` } } } }); await flushPromises(); return router
}
describe('new requirement', () => {
  it('默认读取服务端开发流程的实际版本，创建仍需明确提交', async () => {
    await render(); expect(workflowApi.get).toHaveBeenCalledWith('builtin.workflow.development'); expect(api.create).not.toHaveBeenCalled()
    await wrapper!.get('input').setValue('默认开发'); await wrapper!.get('textarea').setValue('目标')
    await wrapper!.findAll('button').find(item => item.text() === 'project')!.trigger('click')
    api.create.mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' })
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.create.mock.calls[0]![0]).toMatchObject({ templateId: 'builtin.workflow.development', templateRevision: 3 })
  })
  it('从流程库指定的自定义流程优先，不被默认流程覆盖', async () => {
    await render('?template=custom'); expect(workflowApi.get).toHaveBeenCalledOnce(); expect(workflowApi.get).toHaveBeenCalledWith('custom')
  })
  it('迁移旧创建入口时保留未发送目标，仍需用户填名称并明确创建', async () => {
    sessionStorage.setItem('opencode-loopper.designer-draft-prompt', '此前尚未发送的目标')
    await render('?legacyDraft=1')
    expect((wrapper!.get('textarea').element as HTMLTextAreaElement).value).toBe('此前尚未发送的目标')
    expect(api.create).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBe('此前尚未发送的目标')
  })
  it('按项目入口中的准确身份预选项目，创建时保留该项目', async () => {
    api.project.mockResolvedValue({ id: 'selected-project', name: '订单服务', createdAt: '2026-09-29T00:00:00Z' })
    await render('?projectId=selected-project')
    expect(api.project).toHaveBeenCalledWith('selected-project')
    await wrapper!.get('input').setValue('默认开发'); await wrapper!.get('textarea').setValue('目标')
    api.create.mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' })
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.create.mock.calls[0]![0]).toMatchObject({ projectId: 'selected-project', templateId: 'builtin.workflow.development' })
  })
  it('指定项目读取失败仍保留已读取的流程，用户重新选择项目后才可创建', async () => {
    api.project.mockRejectedValue(new Error('network'))
    await render('?projectId=missing'); await wrapper!.get('input').setValue('默认开发'); await wrapper!.get('textarea').setValue('目标')
    await wrapper!.get('form').trigger('submit'); expect(api.create).not.toHaveBeenCalled()
    expect(wrapper!.find('[role="alert"]').exists()).toBe(true)
    await wrapper!.findAll('button').find(item => item.text() === 'project')!.trigger('click')
    api.create.mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' })
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.create.mock.calls[0]![0]).toMatchObject({ projectId: 'project', templateId: 'builtin.workflow.development' })
  })

  it('creates from the selected exact template revision and recovers a lost response using the same request', async () => {
    const router = await render(); await wrapper!.get('input').setValue('新需求'); await wrapper!.get('textarea').setValue('目标说明'); for (const name of ['project', 'template']) await wrapper!.findAll('button').find(item => item.text() === name)!.trigger('click')
    api.create.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' }); await wrapper!.get('form').trigger('submit'); await flushPromises()
    await wrapper!.findAll('button').find(item => item.text() === '重试创建')!.trigger('click'); await flushPromises(); expect(api.create.mock.calls[0]).toEqual(api.create.mock.calls[1]); expect(api.create.mock.calls[0]![0]).toMatchObject({ projectId: 'project', templateId: 'template', templateRevision: 2, title: '新需求', objective: '目标说明' }); expect(router.currentRoute.value.path).toBe('/requirements/created')
  })
  it('preserves a filled form when navigation is declined', async () => { const router = await render(); await wrapper!.get('input').setValue('未提交'); vi.mocked(window.confirm).mockReturnValue(false); await router.push('/requirements'); expect(router.currentRoute.value.path).toBe('/requirements/new') })
})
