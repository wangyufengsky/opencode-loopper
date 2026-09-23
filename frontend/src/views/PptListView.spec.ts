import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { pptApi } from '@/api/ppt'
import { pptDocument } from '@/components/ppt/pptTestFixtures'
import PptListView from './PptListView.vue'

vi.mock('@/api/ppt', () => ({
  pptApi: {
    list: vi.fn(),
    projects: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    upload: vi.fn(),
    send: vi.fn(),
    generate: vi.fn(),
  },
}))
const api = vi.mocked(pptApi)
let wrapper: VueWrapper | undefined
let router: ReturnType<typeof createRouter>

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  setActivePinia(createPinia())
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/ppt', component: PptListView },
      { path: '/ppt/:id', component: { template: '<main>作品工作台</main>' } },
    ],
  })
  api.list.mockResolvedValue({ items: [], nextCursor: undefined, facets: {} })
  api.create.mockResolvedValue({ ...pptDocument('created'), phase: 'BRIEFING', revision: 0 })
  api.get.mockResolvedValue({ ...pptDocument('created'), phase: 'BRIEFING', revision: 0 })
  api.send.mockResolvedValue({} as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('PPT first message', () => {
  it('creates the work and starts discussion without creating a generation authorization', async () => {
    await router.push('/ppt')
    await router.isReady()
    wrapper = mount(PptListView, {
      global: {
        plugins: [router],
        stubs: { Icon: true },
      },
    })
    await flushPromises()
    await wrapper.get('#ppt-first-prompt').setValue('制作一份介绍项目框架的 PPT')
    await wrapper.get('.ppt-prompt-card').trigger('submit')
    await flushPromises()
    expect(api.send).toHaveBeenCalledWith('created', expect.objectContaining({
      text: '制作一份介绍项目框架的 PPT',
      expectedRevision: 0,
      scope: { kind: 'DOCUMENT' },
      idempotencyKey: expect.any(String),
    }))
    expect(api.generate).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/ppt/created')
  })
})
