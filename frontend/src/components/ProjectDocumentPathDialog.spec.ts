import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Project } from '@/types/domain'
import ProjectDocumentPathDialog from './ProjectDocumentPathDialog.vue'

afterEach(() => vi.restoreAllMocks())
const project: Project = { id: 'p', name: '项目', rootPath: '/project', status: 'READY', documentPath: '/project/docs',
  updatedAt: '', version: 4, taskCount: 0, openDesignerSessionCount: 0 }
describe('Project document path', () => {
  it('saves a changed default with its loaded version and updates the project card', async () => {
    const save = vi.spyOn(api, 'updateProjectDocumentPath').mockResolvedValue({ ...project, documentPath: '/project/reports', version: 5 })
    const wrapper = mount(ProjectDocumentPathDialog, { props: { project }, global: { plugins: [ElementPlus], stubs: { teleport: true } } })
    await flushPromises()
    await wrapper.get('input[aria-label="项目文档路径"]').setValue('reports')
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith('p', 'reports', 4)
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ documentPath: '/project/reports', version: 5 })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
  it('keeps the form open after a version conflict', async () => {
    vi.spyOn(api, 'updateProjectDocumentPath').mockRejectedValue(new Error('项目设置已更新，请刷新后重试'))
    const wrapper = mount(ProjectDocumentPathDialog, { props: { project }, global: { plugins: [ElementPlus], stubs: { teleport: true } } })
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '保存')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('项目设置已更新，请刷新后重试')
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})
