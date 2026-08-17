import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerHistoryView from '@/views/DesignerHistoryView.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerHistoryItem, Project } from '@/types/domain'

const { routerPush, routerReplace, routeQuery } = vi.hoisted(() => ({
  routerPush: vi.fn(), routerReplace: vi.fn(), routeQuery: {} as Record<string, string>,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeQuery }),
  useRouter: () => ({ push: routerPush, replace: routerReplace }),
}))

const projects: Project[] = [
  { id: 'project-1', name: 'Alpha', rootPath: '/tmp/alpha', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 2 },
  { id: 'project-2', name: 'Beta', rootPath: '/tmp/beta', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 1 },
]

function design(overrides: Partial<DesignerHistoryItem>): DesignerHistoryItem {
  return {
    id: 'designer-1', projectId: 'project-1', projectName: 'Alpha', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
    createdAt: '2026-08-17T01:00:00Z', updatedAt: '2026-08-17T02:00:00Z', draftId: 'draft-1',
    draftStatus: 'DRAFT_READY', goal: '设计 Alpha', archived: false, ...overrides,
  }
}

function mountHistory() {
  return mount(DesignerHistoryView, {
    global: {
      plugins: [ElementPlus],
      stubs: { PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, Icon: true },
    },
  })
}

beforeEach(() => {
  routerPush.mockReset()
  routerReplace.mockReset()
  for (const key of Object.keys(routeQuery)) delete routeQuery[key]
  sessionStorage.clear()
  setActivePinia(createPinia())
  const store = useTaskStore()
  store.projects = projects
  vi.spyOn(store, 'loadOverview').mockResolvedValue()
})

afterEach(() => {
  vi.restoreAllMocks()
  sessionStorage.clear()
})

describe('DesignerHistoryView', () => {
  it('filters by project and status and sorts matching designs by time', async () => {
    routeQuery.projectId = 'project-1'
    routeQuery.status = 'WAITING_INPUT'
    routeQuery.order = 'oldest'
    vi.spyOn(api, 'listDesignerHistory').mockResolvedValue([
      design({ id: 'newer', goal: '较新的等待设计', updatedAt: '2026-08-17T03:00:00Z' }),
      design({ id: 'older', goal: '较早的等待设计', updatedAt: '2026-08-17T01:00:00Z' }),
      design({ id: 'review', goal: '待确认设计', state: 'REVIEWING', workflowPhase: 'REVIEWING_PACKAGE' }),
      design({ id: 'other-project', projectId: 'project-2', projectName: 'Beta', goal: '其他项目' }),
    ])

    const wrapper = mountHistory()
    await flushPromises()

    const cards = wrapper.findAll('.history-card:not(.skeleton-block)')
    expect(cards.map((card) => card.find('h3').text())).toEqual(['较早的等待设计', '较新的等待设计'])
    expect(wrapper.find('[aria-label="按项目筛选设计"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="按状态筛选设计"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="按更新时间排序设计"]').exists()).toBe(true)
  })

  it('opens continue and edit modes and archives without losing the server record', async () => {
    const item = design({ id: 'designer-actions', goal: '可操作设计' })
    vi.spyOn(api, 'listDesignerHistory').mockResolvedValue([item])
    const archive = vi.spyOn(api, 'archiveDesignerSession').mockResolvedValue()
    sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: item.id, draftId: item.draftId }))

    const wrapper = mountHistory()
    await flushPromises()
    const buttons = () => wrapper.findAll('.history-actions button')

    await buttons().find((button) => button.text().includes('继续'))!.trigger('click')
    await buttons().find((button) => button.text().includes('修改'))!.trigger('click')
    expect(routerPush).toHaveBeenNthCalledWith(1, { path: '/designer', query: { sessionId: item.id, projectId: item.projectId } })
    expect(routerPush).toHaveBeenNthCalledWith(2, { path: '/designer', query: { sessionId: item.id, projectId: item.projectId, mode: 'edit' } })

    await buttons().find((button) => button.text().includes('归档'))!.trigger('click')
    await flushPromises()
    expect(archive).toHaveBeenCalledWith(item.id)
    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(wrapper.find('.history-card:not(.skeleton-block)').exists()).toBe(false)
  })
})
