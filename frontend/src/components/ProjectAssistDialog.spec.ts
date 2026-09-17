import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Project, ProjectAssistConfig } from '@/types/domain'
import ProjectAssistDialog from './ProjectAssistDialog.vue'
afterEach(() => vi.restoreAllMocks())
const project: Project = { id: 'p', name: '项目', rootPath: '/project', status: 'READY', updatedAt: '', taskCount: 0, openDesignerSessionCount: 0 }
const saved: ProjectAssistConfig = { version: 3, credentialConfigured: false, config: { repository: 'group/repo', sources: [{ kind: 'LOG', root: '/service/logs', pattern: '*.log' }] } }
it('saves the version and explicit external log scope without accepting a token', async () => {
  vi.spyOn(api, 'projectAssistConfig').mockResolvedValue(saved)
  const save = vi.spyOn(api, 'saveProjectAssistConfig').mockResolvedValue({ ...saved, version: 4 })
  const wrapper = mount(ProjectAssistDialog, { props: { project }, global: { plugins: [ElementPlus], stubs: { teleport: true, GitCredentialForm: true, RouterLink: true } } })
  await flushPromises()
  await wrapper.get('#tab-gitlab').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('未配置，请设置环境变量')
  await wrapper.findAll('button').find(b => b.text() === '保存配置')!.trigger('click'); await flushPromises()
  expect(save).toHaveBeenCalledWith('p', 3, 'group/repo', saved.config.sources)
  wrapper.unmount()
})
it('ignores a late configuration response after switching projects', async () => {
  let resolve!: (value: ProjectAssistConfig) => void
  vi.spyOn(api, 'projectAssistConfig').mockImplementation(id => id === 'p' ? new Promise(done => { resolve = done }) : Promise.resolve({ ...saved, config: { repository: 'new/project', sources: [] } }))
  const wrapper = mount(ProjectAssistDialog, { props: { project }, global: { plugins: [ElementPlus], stubs: { teleport: true, GitCredentialForm: true, RouterLink: true } } })
  await wrapper.setProps({ project: { ...project, id: 'other' } }); await flushPromises()
  resolve(saved); await flushPromises()
  expect(wrapper.find('input').element.value).toBe('new/project')
  wrapper.unmount()
})
it('preserves edits and displays a version conflict', async () => {
  vi.spyOn(api, 'projectAssistConfig').mockResolvedValue(saved)
  vi.spyOn(api, 'saveProjectAssistConfig').mockRejectedValue(new Error('配置已变化，请刷新后重试'))
  const wrapper = mount(ProjectAssistDialog, { props: { project }, global: { plugins: [ElementPlus], stubs: { teleport: true, GitCredentialForm: true, RouterLink: true } } })
  await flushPromises(); await wrapper.get('#tab-gitlab').trigger('click'); await flushPromises(); await wrapper.findAll('button').find(b => b.text() === '保存配置')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('配置已变化'); expect(wrapper.emitted('close')).toBeUndefined()
  wrapper.unmount()
})
