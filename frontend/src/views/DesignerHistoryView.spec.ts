import { flushPromises, mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import {pageProps} from '@/pages/w3/ppt/testFixture'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {DesignerHistoryPage as DesignerHistoryView} from '@/pages/w2/workflow/DesignerHistoryPage'
import { api } from '@/api/client'

import type { DesignerHistoryItem, Project } from '@/types/domain'

const { routerPush, routerReplace, routeQuery } = vi.hoisted(() => ({
  routerPush: vi.fn(), routerReplace: vi.fn(), routeQuery: {} as Record<string, string>,
}))


const projects: Project[] = [
  { id: 'project-1', name: 'Alpha', rootPath: '/tmp/alpha', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 2 },
  { id: 'project-2', name: 'Beta', rootPath: '/tmp/beta', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 1 },
]

function design(overrides: Partial<DesignerHistoryItem>): DesignerHistoryItem {
  return {
    id: 'designer-1', projectId: 'project-1', projectName: 'Alpha', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
    createdAt: '2026-08-17T01:00:00Z', updatedAt: '2026-08-17T02:00:00Z', draftId: 'draft-1',
    draftStatus: 'DRAFT_READY', goal: '设计 Alpha', archived: false, resumable: true, stopRetryAvailable: false,
    ...overrides,
  }
}

let context:ReturnType<typeof pageProps>|undefined
function mountHistory(){context=pageProps();context.props.route={path:'/designs',fullPath:'/designs',params:{},query:{...routeQuery}};context.props.navigation.go=routerPush.mockResolvedValue(true);return mount(DesignerHistoryView,{props:context.props})}
async function select(view:ReturnType<typeof mountHistory>){await view.get('[data-foundation-component="list"] button').trigger('click');await flushPromises()}

beforeEach(() => {
  routerPush.mockReset()
  routerReplace.mockReset()
  for (const key of Object.keys(routeQuery)) delete routeQuery[key]
  sessionStorage.clear()
  vi.spyOn(api,'getProjects').mockResolvedValue(projects)
})

afterEach(() => {
  context?.retire();context=undefined
  vi.restoreAllMocks()
  sessionStorage.clear()
})

describe('DesignerHistoryView', () => {
  it('filters by project and status and sorts matching designs by time', async () => {
    routeQuery.projectId = 'project-1'
    routeQuery.status = 'WAITING_INPUT'
    routeQuery.order = 'oldest'
    const page = vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [
      design({ id: 'older', goal: '较早的等待设计', updatedAt: '2026-08-17T01:00:00Z' }),
      design({ id: 'newer', goal: '较新的等待设计', updatedAt: '2026-08-17T03:00:00Z' }),
    ], facets: {} })

    const wrapper = mountHistory()
    await flushPromises()

    const cards = wrapper.findAll('[data-foundation-component="list"] button')
    expect(cards.map((card) => card.find('strong').text())).toEqual(['较早的等待设计', '较新的等待设计'])
    expect(wrapper.find('[aria-label="按项目筛选设计"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="按状态筛选设计"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="按更新时间排序设计"]').exists()).toBe(true)
    expect(page).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'project-1', status: 'WAITING_INPUT', order: 'oldest' }))
  })

  it('opens continue and edit modes and archives without losing the server record', async () => {
    const item = design({ id: 'designer-actions', goal: '可操作设计' })
    vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [item], facets: {} })
    const archive = vi.spyOn(api, 'archiveDesignerSession').mockResolvedValue()
    sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: item.id, draftId: item.draftId }))

    const wrapper = mountHistory()
    await flushPromises()
    await select(wrapper)
    const buttons = () => wrapper.findAll('[data-foundation-component="context"] button')

    await buttons().find((button) => button.attributes('data-semantic')==='designer.continue')!.trigger('click')
    await buttons().find((button) => button.attributes('data-semantic')==='designer.editSettings')!.trigger('click')
    expect(routerPush.mock.calls[0]![0]).toEqual({ path: '/designer', query: { sessionId: item.id, projectId: item.projectId } })
    expect(routerPush.mock.calls[1]![0]).toEqual({ path: '/designer', query: { sessionId: item.id, projectId: item.projectId, mode: 'edit' } })

    vi.mocked(api.listDesignerHistoryPage).mockResolvedValue({items:[],facets:{}})
    await buttons().find((button) => button.attributes('data-semantic')==='designer.archive')!.trigger('click')
    await flushPromises()
    expect(archive).toHaveBeenCalledWith(item.id)
    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(wrapper.find('[data-foundation-component="list"] button').exists()).toBe(false)
  })

  it('shows confirmed task designs as read-only history without continue, edit, or archive actions', async () => {
    vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [
      design({
        id: 'designer-confirmed', goal: '已完成任务的设计', state: 'COMPLETED', workflowPhase: 'COMPLETED',
        draftStatus: 'CONFIRMED', taskId: 'task-completed', taskState: 'COMPLETED',
      }),
    ], facets: {} })

    const wrapper = mountHistory()
    await flushPromises()

    await select(wrapper)
    const card = wrapper.get('[data-foundation-component="context"]')
    expect(card.text()).toContain('已确认成任务')
    expect(card.text()).toContain('任务：已确认完成')
    expect(card.text()).not.toContain('继续')
    expect(card.text()).not.toContain('修改')
    expect(card.text()).not.toContain('归档')

    await card.get('a[href="/tasks/task-completed/design"]').trigger('click')
    expect(routerPush.mock.calls[0]![0]).toBe('/tasks/task-completed/design')
  })

  it('keeps a stopped and archived design as a read-only cancelled record', async () => {
    vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [
      design({ id: 'designer-cancelled', state: 'CANCELLED', workflowPhase: 'FAILED', archived: true, resumable: false }),
    ], facets: {} })

    const wrapper = mountHistory()
    await flushPromises()

    await select(wrapper)
    const card = wrapper.get('[data-foundation-component="context"]')
    expect(card.text()).toContain('已取消')
    expect(card.text()).toContain('只读记录')
    expect(card.text()).not.toContain('继续')
    expect(card.text()).not.toContain('修改')
    expect(card.text()).not.toContain('恢复')
  })

  it('shows only retry-stop while the server reports STOPPING', async () => {
    const retry = vi.spyOn(api, 'stopDesignerSession').mockResolvedValue({
      stopStatus: 'STOPPING', archived: false, stoppedSessions: 1, failedSessions: 0, pendingFinalizations: 1,
    })
    vi.spyOn(api, 'listDesignerHistoryPage').mockResolvedValue({ items: [
      design({ id: 'designer-stopping', state: 'STOPPING', resumable: false, stopRetryAvailable: true }),
    ], facets: { STOP_RETRY_TOTAL: 1 } })

    const wrapper = mountHistory()
    await flushPromises()
    await select(wrapper)
    const card = wrapper.get('[data-foundation-component="context"]')
    expect(card.text()).toContain('重试停止')
    expect(card.text()).not.toContain('继续')
    expect(card.text()).not.toContain('修改')
    expect(card.text()).not.toContain('归档')
    await card.get('button[data-semantic="designer.retryStop"]').trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledWith('designer-stopping')
  })
})
