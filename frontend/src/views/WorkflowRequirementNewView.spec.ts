import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'

import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { template } from '@/components/workflow/workflowTestFixtures'
import { NewRequirementPage } from '@/pages/w5/requirements/NewRequirementPage'
import { summary } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { get: vi.fn(), list: vi.fn() } }))
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { create: vi.fn(), project: vi.fn(), projects: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: Awaited<ReturnType<typeof mountPageApplication>> | undefined
beforeEach(() => { sessionStorage.clear(); vi.resetAllMocks(); vi.mocked(workflowApi.get).mockResolvedValue(template({ id: 'builtin.workflow.development', title: '默认开发流程', revision: 3 })); api.projects.mockResolvedValue({items:[{id:'project',name:'project',createdAt:''}],facets:{},nextCursor:undefined}); vi.mocked(workflowApi.list).mockResolvedValue({items:[summary({id:'template',title:'template',headRevision:2})],nextCursor:null}) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render(query='') { wrapper=await mountPageApplication(NewRequirementPage,'/requirements/new'+query,['/requirements/new','/requirements/:id','/requirements']);return wrapper }
async function choose(kind:'project'|'template') { await wrapper!.get(`button[data-semantic="${kind==='project'?'workflow.chooseProject':'workflow.chooseTemplate'}"]`).trigger('click'); await flushPromises();if(kind==='template')vi.mocked(workflowApi.get).mockResolvedValue(template({id:'template',title:'template',revision:2}));await wrapper!.get('aside:not([hidden]) .w5-list button').trigger('click');await flushPromises() }
describe('new requirement', () => {
  it('默认读取服务端开发流程的实际版本，创建仍需明确提交', async () => {
    await render(); expect(workflowApi.get).toHaveBeenCalledWith('builtin.workflow.development'); expect(api.create).not.toHaveBeenCalled()
    await wrapper!.get('input').setValue('默认开发'); await wrapper!.get('textarea').setValue('目标')
    await choose('project')
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
    await choose('project')
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    api.create.mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' })
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.create.mock.calls[0]![0]).toMatchObject({ projectId: 'project', templateId: 'builtin.workflow.development' })
  })

  it('creates from the selected exact template revision and recovers a lost response using the same request', async () => {
    const router = await render(); await wrapper!.get('input').setValue('新需求'); await wrapper!.get('textarea').setValue('目标说明'); for (const name of ['project', 'template'] as const) await choose(name)
    api.create.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'created', revision: 1, version: 0, layoutVersion: 0, state: 'PLANNING' }); await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(wrapper!.findAll('fieldset').every(fieldset => fieldset.attributes('disabled') !== undefined)).toBe(true)
    await wrapper!.findAll('button').find(item => item.attributes('data-semantic') === 'workflow.retryCreate')!.trigger('click'); await flushPromises(); expect(api.create.mock.calls[0]).toEqual(api.create.mock.calls[1]); expect(api.create.mock.calls[0]![0]).toMatchObject({ projectId: 'project', templateId: 'template', templateRevision: 2, title: '新需求', objective: '目标说明' }); expect(router.router.state.location.pathname).toBe('/requirements/created')
  })
  it('preserves a filled form when navigation is declined', async () => { const router = await render(); await wrapper!.get('input').setValue('未提交'); const navigation=router.navigate('/requirements');await flushPromises();await resolveDialog(wrapper!,false);await navigation;expect(router.router.state.location.pathname).toBe('/requirements/new') })
  it('recovers each initial selection independently without hiding another failed selection', async () => {
    vi.mocked(workflowApi.get).mockRejectedValue(new Error('network')); api.project.mockRejectedValue(new Error('network'))
    await render('?projectId=missing')
    expect(wrapper!.findAll('[role="alert"]')).toHaveLength(2)
    await choose('project')
    expect(wrapper!.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper!.get('[role="alert"]').text()).toContain('指定流程无法读取')
    await choose('template')
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    expect(api.create).not.toHaveBeenCalled()
  })
})
