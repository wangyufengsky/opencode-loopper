import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {createElement} from 'react'
import {application,flushPromises} from '@/pages/w6-tests/ordinary/application'
import {createTaskApplicationOwner} from '@/stores/taskStore'
import {TasksPage} from '@/pages/w2/core/TasksPage'
import type {Task} from '@/types/domain'
const tasks: Task[] = [
  { id: 'new-a', projectId: 'project-a', projectName: '项目 A', title: 'A 的新任务', goal: 'A new', branch: 'DIRECT', worktreePath: '/a', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-05T08:00:00Z', updatedAt: '2026-08-05T09:00:00Z' },
  { id: 'old-a', projectId: 'project-a', projectName: '项目 A', title: 'A 的旧任务', goal: 'A old', branch: 'DIRECT', worktreePath: '/a', status: 'FAILED', hasDesignHistory: true, attemptCount: 2, maxAttempts: 3, createdAt: '2026-08-03T08:00:00Z', updatedAt: '2026-08-03T09:00:00Z' },
  { id: 'middle-b', projectId: 'project-b', projectName: '项目 B', title: 'B 的任务', goal: 'B middle', branch: 'DIRECT', worktreePath: '/b', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-04T08:00:00Z', updatedAt: '2026-08-04T09:00:00Z' },
]

let store:ReturnType<typeof createTaskApplicationOwner>,route='/tasks',router:{currentRoute:{value:{path:string;query:Record<string,string|string[]|null|undefined>}},push:(to:string)=>Promise<unknown>}
beforeEach(()=>{store=createTaskApplicationOwner();store.usingDemo=true;store.tasks=structuredClone(tasks);store.taskItems=structuredClone(tasks);route='/tasks';router={currentRoute:{value:{path:'/tasks',query:{}}},push:async to=>{route=to}}})
afterEach(()=>store.dispose())
const useTaskStore=()=>store
async function mountView(){const page=await application({taskOwner:store,initialEntries:[route],routes:[{path:'/tasks',Component:TasksPage},...['/tasks/:id','/tasks/:id/design','/template-tasks/document-runs/:id','/designer','/projects'].map(path=>({path,element:createElement('main')}))]});router=page.navigation;return page.view}
async function selectFirst(w:Awaited<ReturnType<typeof mountView>>){await w.get('tbody tr').trigger('click')}
describe('Tasks filters and design history', () => {
  it('keeps report completion separate from a linked execution awaiting disposition', async () => {
    const store = useTaskStore()
    store.usingDemo = false
    store.projects = [{ id: 'project-a', name: '项目 A', rootPath: '/a', status: 'READY', updatedAt: 'now', taskCount: 1, openDesignerSessionCount: 0 }]
    vi.spyOn(store, 'loadProjects').mockResolvedValue([])
    vi.spyOn(store, 'loadTaskSummaries').mockResolvedValue(undefined)
    store.taskItems = [{ ...structuredClone(tasks[0]!), id: 'document', status: 'AWAITING_DECISION',
      documentRunId: 'document', documentState: 'COMPLETED', linkedTaskId: 'linked', sourceTemplateId: 'REQUIREMENT_DEVELOPMENT' }]
    const wrapper = await mountView()
    await flushPromises()
    await selectFirst(wrapper);expect(wrapper.find('button[data-semantic="task.archive"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('等待处置')
    store.taskItems=store.taskItems.map(task=>({...task,status:'COMPLETED'}))
    await flushPromises()
    expect(wrapper.find('button[data-semantic="task.archive"]').exists()).toBe(true)
    wrapper.unmount()
  })
  it('routes template filters to the server and keeps the selection in the URL', async () => {
    const store = useTaskStore()
    store.usingDemo = false
    vi.spyOn(store, 'loadProjects').mockResolvedValue([])
    const load = vi.spyOn(store, 'loadTaskSummaries').mockResolvedValue(undefined)
    await router.push('/tasks?type=template')
    const wrapper = await mountView()
    await flushPromises()
    expect(load).toHaveBeenCalledWith(expect.objectContaining({ taskType: 'TEMPLATE' }), false)
    expect((wrapper.get('select[aria-label="按任务类型筛选"]').element as HTMLSelectElement).value).toBe('TEMPLATE')
    await wrapper.get('select[aria-label="按任务类型筛选"]').setValue('STANDARD')
    await flushPromises()
    expect(router.currentRoute.value.query.type).toBe('standard')
    wrapper.unmount()
  })

  it('filters by project and sorts by updated time in both directions', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的新任务', 'B 的任务', 'A 的旧任务'])
    await wrapper.get('select[aria-label="按项目筛选任务"]').setValue('project-a')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的新任务', 'A 的旧任务'])

    await wrapper.get('select[aria-label="按项目筛选任务"]').setValue('ALL')
    await wrapper.get('select[aria-label="按更新时间排序"]').setValue('OLDEST')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['A 的旧任务', 'B 的任务', 'A 的新任务'])
  })

  it('keeps search, grouping, archive scope and status in the URL', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('[aria-label="搜索任务"]').setValue('B 的任务')
    await flushPromises()
    expect(wrapper.findAll('.task-link').map((link) => link.text())).toEqual(['B 的任务'])
    await vi.waitFor(()=>expect(router.currentRoute.value.query.q).toBe('B 的任务'))

    await wrapper.get('[aria-label="搜索任务"]').setValue('')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(wrapper.findAll('.task-group-header')).toHaveLength(2)
    expect(router.currentRoute.value.query.group).toBe('project')

    await selectFirst(wrapper); await wrapper.get('button[data-semantic="task.archive"]').trigger('click')
    await flushPromises()
    expect(useTaskStore().tasks.filter((task) => task.archived)).toHaveLength(1)
    await wrapper.get('select[aria-label="选择归档范围"]').setValue('ARCHIVED')
    await flushPromises()
    expect(wrapper.findAll('.task-link')).toHaveLength(1)
    expect(router.currentRoute.value.query.archive).toBe('archived')
  })

  it('permanently deletes only an archived task after explicit confirmation', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await selectFirst(wrapper); await wrapper.get('button[data-semantic="task.archive"]').trigger('click')
    await wrapper.get('select[aria-label="选择归档范围"]').setValue('ARCHIVED')
    await flushPromises()

    await selectFirst(wrapper);const remove = wrapper.get('button[data-semantic="task.delete"]')
    await remove.trigger('click')
    await flushPromises()

    const dialog=wrapper.get('[role="dialog"]');expect(dialog.text()).toContain('项目文件、Git 分支与 worktree 不会删除');expect(useTaskStore().tasks).toHaveLength(3);await dialog.get('[data-semantic="task.delete"]').trigger('click');await flushPromises()
    expect(useTaskStore().tasks).toHaveLength(2)
    expect(wrapper.findAll('.task-link')).toHaveLength(0)
  })

  it('guides first-time users to register a project before opening Designer', async () => {
    const store = useTaskStore()
    store.usingDemo = false
    store.tasks = []
    store.projects = []
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('先登记一个项目')
    const register = wrapper.findAll('button').find((button) => button.text().includes('登记项目'))
    await register!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects')
  })

  it('opens the persisted design and LoopSpec history for a task', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await selectFirst(wrapper);expect(wrapper.get('[data-semantic="task.openDesign"]').exists()).toBe(true);await wrapper.get('[data-semantic="task.openDesign"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/tasks/new-a/design')
  })

  it('shows the persisted RETRY_WAIT countdown in the task list', async () => {
    useTaskStore().tasks=[{
      id: 'retry-a', projectId: 'project-a', projectName: '项目 A', title: '等待重试任务', goal: 'retry',
      branch: 'DIRECT', worktreePath: '/a', status: 'RETRY_WAIT', hasDesignHistory: true,
      attemptCount: 1, maxAttempts: 3, retryCause: 'RATE_LIMIT', retryOrdinal: 1,
      retryDelaySeconds: 60, retryScheduledAt: new Date().toISOString(),
      retryDueAt: new Date(Date.now() + 30_000).toISOString(),
      createdAt: '2026-08-18T08:00:00Z', updatedAt: '2026-08-18T09:00:00Z',
    },...useTaskStore().tasks]

    const wrapper = await mountView()
    await flushPromises()
    const row = wrapper.findAll('tbody tr').find((candidate) => candidate.text().includes('等待重试任务'))

    expect(row?.text()).toMatch(/(?:29|30)s/)
  })
})
