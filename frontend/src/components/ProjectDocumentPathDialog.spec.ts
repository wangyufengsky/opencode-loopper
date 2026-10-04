import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Project } from '@/types/domain'
import { createProjectsController } from '@/pages/w2/core/projectsController'
import { coreFixture } from '@/pages/w2/core/coreTestHelpers'

afterEach(() => vi.restoreAllMocks())
const project: Project = { id: 'p', name: '项目', rootPath: '/project', status: 'READY', documentPath: '/project/docs', updatedAt: '', version: 4, taskCount: 0, openDesignerSessionCount: 0 }
function setup(){const f=coreFixture({projects:[project]}),owner=createProjectsController(f.port);owner.open('document',project);return {owner,port:f.port}}
describe('Project document path', () => {
  it('saves the directory selected through the system picker', async () => {
    vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: true, path: '/tmp/selected reports' })
    const save = vi.spyOn(api, 'updateProjectDocumentPath').mockResolvedValue({ ...project, documentPath: '/tmp/selected reports', version: 5 })
    const {owner}=setup();await owner.pick('documentPath');await owner.saveDocument()
    expect(save).toHaveBeenCalledWith('p', '/tmp/selected reports', 4)
    owner.retire(true)
  })
  it('saves a changed default with its loaded version and updates the project card', async () => {
    const save = vi.spyOn(api, 'updateProjectDocumentPath').mockResolvedValue({ ...project, documentPath: '/project/reports', version: 5 })
    const {owner,port}=setup();owner.changeDocument('reports');await owner.saveDocument()
    expect(save).toHaveBeenCalledWith('p', 'reports', 4)
    expect(port.replaceProject).toHaveBeenCalledWith(expect.objectContaining({documentPath:'/project/reports',version:5}))
    expect(owner.getSnapshot().context).toBeUndefined()
    owner.retire(true)
  })
  it('keeps the form open after a version conflict', async () => {
    vi.spyOn(api, 'updateProjectDocumentPath').mockRejectedValue(new Error('项目设置已更新，请刷新后重试'))
    const {owner}=setup();await owner.saveDocument()
    expect(owner.getSnapshot().error).toContain('项目设置已更新，请刷新后重试')
    expect(owner.getSnapshot().context).toBe('document')
    expect(owner.canLeave().kind).toBe('BLOCK')
    owner.retire(true)
  })
})
