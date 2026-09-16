import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { useDocumentTemplateStore } from '@/stores/documentTemplateStore'
import type { TemplateTaskDefinition, TemplateTaskCatalog } from '@/types/domain'
import TemplateTasksView from './TemplateTasksView.vue'
vi.mock('@/api/client', () => ({ ApiError: class extends Error {}, api: { createTemplateTask: vi.fn(), startTemplateTask: vi.fn(), templateProjects: vi.fn(), templateProject: vi.fn(), templateCatalog: vi.fn(), templateBranches: vi.fn(), documentTemplateRequest: vi.fn() } }))
const definitions: TemplateTaskDefinition[] = [
  { id: 'SNAPSHOT_CODE_REVIEW', version: '1', title: '代码审查', workflow: 'SNAPSHOT_CODE_REVIEW', description: '冻结版本审查', category: '审查', inputs: { documents: false, branch: true, dates: true, extensions: [], maxFiles: 0, maxFileMiB: 0, maxTotalMiB: 0 } },
  { id: 'REQUIREMENT_DEVELOPMENT', version: '1', title: '需求开发', description: '从文档开发', category: '需求', inputs: { documents: true, branch: false, dates: false, extensions: ['md', 'docx', 'pdf'], maxFiles: 10, maxFileMiB: 20, maxTotalMiB: 50 } },
  { id: 'REQUIREMENT_CODE_REVIEW', version: '1', title: '需求代码评审', description: '静态需求评审', category: '需求', inputs: { documents: true, branch: true, dates: false, extensions: ['md', 'docx', 'pdf'], maxFiles: 10, maxFileMiB: 20, maxTotalMiB: 50 } },
] as TemplateTaskDefinition[]
beforeEach(() => {
  vi.clearAllMocks(); sessionStorage.clear(); setActivePinia(createPinia())
  vi.mocked(api.templateProjects).mockResolvedValue({ items: [], facets: {} })
  vi.mocked(api.templateProject).mockResolvedValue({ id: 'inherited', name: '入口项目', createdAt: 'now', documentPath: null })
  vi.mocked(api.templateCatalog).mockResolvedValue({ templates: definitions.slice(1), dimensions: [], defaultStartDate: '2026-09-01', defaultEndDate: '2026-09-14' } as unknown as TemplateTaskCatalog)
  const branch = { id: 'local:main', label: 'main', ref: 'refs/heads/main', remote: null }
  vi.mocked(api.templateBranches).mockResolvedValue({ page: { items: [branch], facets: {}, nextCursor: null }, defaultBranch: branch, defaultBranchId: branch.id, remoteAvailable: true } as Awaited<ReturnType<typeof api.templateBranches>>)
})
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/template-tasks', component: TemplateTasksView }, { path: '/template-tasks/document-runs/:id', component: { template: '<div />' } }] })
  await router.push('/template-tasks?projectId=inherited'); await router.isReady()
  const wrapper = mount(TemplateTasksView, { global: { plugins: [ElementPlus, router], stubs: { Icon: true, PageHeader: true, DirectoryPathInput: true } } })
  await flushPromises(); return { wrapper, router }
}
it('inherits the project and starts development with files alone; review adds only a branch', async () => {
  const start = vi.spyOn(useDocumentTemplateStore(), 'start').mockResolvedValue('created')
  const { wrapper, router } = await render()
  expect(api.templateProject).toHaveBeenCalledWith('inherited'); expect(api.templateBranches).not.toHaveBeenCalled()
  expect(wrapper.find('[aria-label="开始日期"]').exists()).toBe(false); expect(wrapper.find('[aria-label="分支"]').exists()).toBe(false)
  const file = new File(['# 需求\n必须鉴权'], '需求.md', { type: 'text/markdown' })
  Object.defineProperty(wrapper.get('#requirement-files').element, 'files', { configurable: true, value: [file] })
  await wrapper.get('#requirement-files').trigger('change')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(start).toHaveBeenCalledWith({ templateId: 'REQUIREMENT_DEVELOPMENT', templateVersion: '1', projectId: 'inherited' }, [file])
  expect(router.currentRoute.value.path).toBe('/template-tasks/document-runs/created')
  wrapper.unmount()
})
it('shows branch selection without dates for review and rejects an unsupported file', async () => {
  const start = vi.spyOn(useDocumentTemplateStore(), 'start').mockResolvedValue('created')
  const { wrapper } = await render()
  await wrapper.findAll('.template-choice')[1]!.trigger('click'); await flushPromises()
  expect(api.templateBranches).toHaveBeenCalledWith('inherited', '', undefined)
  expect(wrapper.find('[aria-label="分支"]').exists()).toBe(true); expect(wrapper.find('[aria-label="开始日期"]').exists()).toBe(false)
  Object.defineProperty(wrapper.get('#requirement-files').element, 'files', { configurable: true, value: [new File(['old'], '旧需求.doc')] })
  await wrapper.get('#requirement-files').trigger('change'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.text()).toContain('请转换旧 DOC'); expect(start).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('defaults to date increment and sends no dates in full review', async () => {
  vi.mocked(api.templateCatalog).mockResolvedValue({ templates: definitions, dimensions: [], defaultStartDate: '2026-09-01', defaultEndDate: '2026-09-14' } as unknown as TemplateTaskCatalog)
  vi.mocked(api.createTemplateTask).mockResolvedValue({ id: 'created' } as Awaited<ReturnType<typeof api.createTemplateTask>>)
  const { wrapper } = await render()
  expect(wrapper.find('[aria-label="开始日期"]').exists()).toBe(true)
  expect(wrapper.text()).toContain('24:00')
  const selects = wrapper.findAllComponents({ name: 'ElSelect' })
  const mode = selects.find(s => s.props('modelValue') === 'DATE_INCREMENTAL')!
  mode.vm.$emit('update:modelValue', 'FULL'); await flushPromises()
  expect(wrapper.find('[aria-label="开始日期"]').exists()).toBe(false)
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(api.createTemplateTask).toHaveBeenCalledWith(expect.objectContaining({ templateId: 'SNAPSHOT_CODE_REVIEW', reviewMode: 'FULL' }))
  const request = vi.mocked(api.createTemplateTask).mock.calls[0]![0]
  expect(request).not.toHaveProperty('startDate'); expect(request).not.toHaveProperty('endDate')
  wrapper.unmount()
})

it('shows the authentication cause and lets an explicit local branch start review', async () => {
  vi.mocked(api.templateCatalog).mockResolvedValue({ templates: definitions, dimensions: [], defaultStartDate: '2026-09-01', defaultEndDate: '2026-09-14' } as unknown as TemplateTaskCatalog)
  const branch = { id: 'local:refs/heads/main', label: 'main（本地）', ref: 'refs/heads/main', remote: null }
  vi.mocked(api.templateBranches).mockResolvedValue({ page: { items: [branch], facets: {}, nextCursor: null }, defaultBranch: null, defaultBranchId: null, remoteAvailable: false, remoteProblems: ['Git 身份认证失败，请检查启动程序所用账号的凭据或 SSH 配置'] })
  vi.mocked(api.createTemplateTask).mockResolvedValue({ id: 'created' } as Awaited<ReturnType<typeof api.createTemplateTask>>)
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('Git 身份认证失败')
  const select = wrapper.findAllComponents({ name: 'ElSelect' }).find(s => s.props('ariaLabel') === '分支')!
  select.vm.$emit('update:modelValue', branch.id); await flushPromises()
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(api.createTemplateTask).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'inherited', branchId: branch.id }))
  wrapper.unmount()
})
