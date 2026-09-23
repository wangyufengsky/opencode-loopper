import { flushPromises, mount } from '@vue/test-utils'
import { ElButton, ElInput } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { SourceTemplatePreview, TemplateTaskDefinition } from '@/types/domain'
import SourceTemplateFields from './SourceTemplateFields.vue'

const unit: TemplateTaskDefinition = { id: 'UNIT_TEST_DEVELOPMENT', version: '1', title: '单元测试开发', description: '',
  contentRepairLimit: 0, stages: [], scoringVersion: null,
  inputs: { documents: false, branch: false, dates: false, extensions: [], maxFiles: 0, maxFileMiB: 0, maxTotalMiB: 0, sourcePath: true, testOutputPath: true } }
const design: TemplateTaskDefinition = { ...unit, id: 'DETAILED_DESIGN_WRITING',
  inputs: { ...unit.inputs!, testOutputPath: false, documentOutputPath: true } }
const preview: SourceTemplatePreview = { sourcePath: 'src/main', testOutputPath: null, documentPath: null,
  manifestSha256: 'abc', targetCount: 2, excludedCount: 1, moduleCount: 1, truncated: false,
  files: [{ path: 'src/main/Service.java', target: true, sizeBytes: 10, sha256: 'abc', exclusion: null }],
  testProfile: { manifestSha256: 'abc', modules: [{ root: '.', framework: 'junit', sourcePaths: ['src/main/Service.java'],
    testRoots: ['src/test/java'], fixtureRoots: ['src/test/resources'], command: ['mvn', 'test'] }] }, configurationProblem: null }
beforeEach(() => { setActivePinia(createPinia()); sessionStorage.clear() })
afterEach(() => vi.restoreAllMocks())
function render(definition = unit) {
  return mount(SourceTemplateFields, { props: { definition, projectId: 'p1', documentPath: 'docs' }, global: { plugins: [ElButton, ElInput], stubs: { Icon: true } } })
}
it('requires a current preflight and sends only capability-supported output fields', async () => {
  vi.spyOn(api, 'sourcePreview').mockResolvedValue(preview)
  const create = vi.spyOn(api, 'createSourceTemplate').mockResolvedValue({ id: 'new-run' } as never)
  const wrapper = render()
  await wrapper.get('input[aria-label="源码路径"]').setValue('src/main')
  await wrapper.vm.submit(); expect(create).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.text() === '检查处理范围')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('2 个处理文件'); expect(wrapper.text()).toContain('src/test/java')
  expect(await wrapper.vm.submit()).toBe('new-run')
  expect(create).toHaveBeenCalledWith(expect.objectContaining({ templateId: unit.id, projectId: 'p1', sourcePath: 'src/main' }))
  expect(create.mock.calls[0]![0]).not.toHaveProperty('documentPath')
  await wrapper.get('textarea').setValue('优先异常边界'); await wrapper.vm.submit()
  expect(create).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
it('ignores late preflight and folder selection after switching project and template', async () => {
  let resolve!: (value: SourceTemplatePreview) => void
  vi.spyOn(api, 'sourcePreview').mockImplementation(() => new Promise(done => { resolve = done }))
  let choose!: (value: { selected: boolean; path: string }) => void
  vi.spyOn(api, 'pickProjectDirectory').mockImplementation(() => new Promise(done => { choose = done }))
  const wrapper = render()
  await wrapper.get('input[aria-label="源码路径"]').setValue('old')
  await wrapper.findAll('button').find(button => button.text() === '检查处理范围')!.trigger('click')
  await wrapper.get('button[aria-label="选择源码路径文件夹"]').trigger('click')
  await wrapper.setProps({ projectId: 'p2', definition: design, documentPath: 'new/docs' })
  resolve(preview); choose({ selected: true, path: '/old/project' }); await flushPromises()
  expect(wrapper.find('[aria-label="处理范围预览"]').exists()).toBe(false)
  expect(wrapper.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('')
  expect(wrapper.get<HTMLInputElement>('input[aria-label="文档目录"]').element.value).toBe('new/docs')
  expect(wrapper.find('input[aria-label="测试目录"]').exists()).toBe(false)
  wrapper.unmount()
})
it('shows configuration failures and preserves input when the picker is cancelled', async () => {
  vi.spyOn(api, 'sourcePreview').mockResolvedValue({ ...preview, configurationProblem: '未找到 JUnit 配置，请先处理配置', testProfile: null })
  vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: false })
  const create = vi.spyOn(api, 'createSourceTemplate')
  const wrapper = render()
  await wrapper.get('input[aria-label="源码路径"]').setValue('src/main')
  await wrapper.get('button[aria-label="选择源码路径文件夹"]').trigger('click'); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('src/main')
  await wrapper.findAll('button').find(button => button.text() === '检查处理范围')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('未找到 JUnit 配置')
  await wrapper.vm.submit(); expect(create).not.toHaveBeenCalled()
  wrapper.unmount()
})
it('renders a picker failure without the full Element Plus plugin and keeps the typed path', async () => {
  vi.spyOn(api, 'pickProjectDirectory').mockRejectedValue(new Error('文件夹选择失败，请手动填写路径'))
  const wrapper = render()
  await wrapper.get('input[aria-label="源码路径"]').setValue('src/main')
  await wrapper.get('button[aria-label="选择源码路径文件夹"]').trigger('click'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('文件夹选择失败，请手动填写路径')
  expect(wrapper.get<HTMLInputElement>('input[aria-label="源码路径"]').element.value).toBe('src/main')
  wrapper.unmount()
})
