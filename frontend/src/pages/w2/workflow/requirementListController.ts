import { workflowRuns } from '@/api/workflowRuns'
import type { TemplateProjectChoice, WorkflowRequirementSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { pageController } from './controllerCore'

export interface RequirementListState {
  rows: WorkflowRequirementSummary[]; cursor: string; busy: boolean; error: string; selectedId: string
  project: TemplateProjectChoice | null; projects: TemplateProjectChoice[]; projectQuery: string; projectCursor: string; projectBusy: boolean; projectError: string
}
export function createRequirementListController(port = workflowRuns, projectId = '') {
  const core = pageController<RequirementListState>('requirement-list', { rows: [], cursor: '', busy: false, error: '', selectedId: '',
    project: null, projects: [], projectQuery: '', projectCursor: '', projectBusy: false, projectError: '' })
  const state = core.owner.getSnapshot
  async function load(more = false) {
    if (!core.active()) return
    const current = core.ticket('list'), s = state()
    core.patch({ busy: true, error: '', ...(!more ? { rows: [], cursor: '', selectedId: '' } : {}) })
    try {
      const page = await port.list(s.project?.id, more ? s.cursor : '')
      if (current.current()) core.patch({ rows: more ? [...s.rows, ...page.items] : page.items, cursor: page.nextCursor ?? '' })
    } catch (failure) { if (current.current()) core.patch({ error: userFacingError(failure, '需求任务暂时无法读取，请重试。') }) }
    finally { if (current.current()) core.patch({ busy: false }) }
  }
  async function projects(query = state().projectQuery, more = false) {
    if (!core.active()) return
    const current = core.ticket('projects'), s = state()
    core.patch({ projectQuery: query, projectBusy: true, projectError: '' })
    try {
      const page = await port.projects(query, more ? s.projectCursor : '')
      if (current.current()) core.patch({ projects: more ? [...s.projects, ...page.items] : page.items, projectCursor: page.nextCursor ?? '' })
    } catch (failure) { if (current.current()) core.patch({ projectError: userFacingError(failure, '项目列表读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ projectBusy: false }) }
  }
  core.setStart(() => {
    void projects()
    if (!projectId || state().project) void load()
    else {
      const current = core.ticket('project')
      void port.project(projectId).then(project => { if (current.current()) { core.patch({ project }); void load() } }, failure => {
        if (current.current()) core.patch({ projectError: userFacingError(failure, '深链项目无法读取，请重新选择项目。') })
      })
    }
  })
  return { ...core.owner, load, projects,
    setProject(project: TemplateProjectChoice | null) { core.invalidate('project', 'list'); core.patch({ project }); void load() },
    select(selectedId: string) { core.patch({ selectedId }) },
  }
}
