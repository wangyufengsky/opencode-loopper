import { act, fireEvent } from '@testing-library/react'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pptApi } from '@/api/ppt'
import { usePptStore } from '@/stores/pptStore'
import { pptAgent, pptCapabilities, pptDeck, pptDocument, pptPlan, pptText } from '@/components/ppt/pptTestFixtures'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'
import { installCanvasRuntime } from '@/migration/canvasRuntimeVue'
import PptStudioView from './PptStudioView.vue'

vi.mock('@/api/ppt', () => ({
  pptApi: {
    get: vi.fn(), deck: vi.fn(), plan: vi.fn(), sources: vi.fn(), jobs: vi.fn(),
    revisions: vi.fn(), messages: vi.fn(), agent: vi.fn(), generation: vi.fn(),
    capabilities: vi.fn(), events: vi.fn(), operations: vi.fn(), artifactUrl: vi.fn(),
  },
}))
const api = vi.mocked(pptApi)
let wrapper: VueWrapper | undefined
let router: ReturnType<typeof createRouter>
let pinia: ReturnType<typeof createPinia>
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  localStorage.clear()
  vi.stubGlobal('crypto', webcrypto)
  pinia = createPinia()
  setActivePinia(pinia)
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/ppt/:id', component: PptStudioView },
    { path: '/ppt', component: { template: '<main>作品列表</main>' } },
  ] })
  api.get.mockImplementation(async id => pptDocument(id))
  api.deck.mockResolvedValue(pptDeck())
  api.plan.mockResolvedValue({ plan: pptPlan(), revision: 3 })
  api.sources.mockResolvedValue({ sources: [], assets: [] })
  api.jobs.mockResolvedValue([])
  api.revisions.mockResolvedValue({ items: [], facets: {} })
  api.messages.mockResolvedValue({ items: [], facets: {} })
  api.agent.mockResolvedValue(pptAgent())
  api.generation.mockResolvedValue(null)
  api.capabilities.mockResolvedValue(pptCapabilities())
  api.events.mockImplementation(() => ({ close: vi.fn() }) as unknown as EventSource)
})
afterEach(() => {
  act(() => wrapper?.unmount())
  wrapper = undefined
  usePptStore().close()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
async function open() {
  await router.push('/ppt/first')
  await router.isReady()
  await act(async () => {
    wrapper = mount(defineComponent({ setup: () => () => h(RouterView) }), { attachTo: document.body, global: {
      plugins: [pinia, router, { install: (app: App) => installCanvasRuntime(app, router) }],
      stubs: { Icon: true, PptChat: true, PptDetailsDialog: true, PptDownloadControl: true, PptGenerationStatus: true },
    } })
    await flushPromises()
  })
}
async function selectText() {
  await act(async () => {
    await wrapper!.get('.ppt-manual-toggle').trigger('click')
    await flushPromises()
    fireEvent.click(wrapper!.get('.ppt-canvas-object').element)
    await flushPromises()
  })
}
async function navigate(path: string) {
  await act(async () => { await router.push(path); await flushPromises() })
}

describe('PPT workspace recovery guards with the actual React canvas', () => {
  it('blocks busy and volatile pending navigation without a property draft until the original request is recovered', async () => {
    await open()
    await selectText()
    const store = usePptStore()
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('Storage unavailable', 'SecurityError')
    })
    let reject!: (reason: Error) => void
    api.operations.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
    fireEvent.keyDown(wrapper!.get('.ppt-canvas-object').element, { key: 'ArrowRight' })
    await act(async () => { await flushPromises() })
    const original = api.operations.mock.calls[0]!
    expect(store.busy).toBe(true)
    expect(sessionStorage.getItem('loopper.ppt.pending.first')).toBeNull()
    expect(sessionStorage.getItem('loopper.ppt.element.first.text-1')).toBeNull()
    const confirm = vi.spyOn(window, 'confirm')
    for (const path of ['/ppt', '/ppt/second', '/ppt/first?view=history']) {
      await navigate(path)
      expect(router.currentRoute.value.fullPath).toBe('/ppt/first')
    }
    expect(confirm).not.toHaveBeenCalled()
    const unload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(unload)
    expect(unload.defaultPrevented).toBe(true)
    await act(async () => { reject(new Error('network receipt lost')); await flushPromises() })
    storage.mockRestore()
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ ppt: 'vue' }))
    await navigate('/ppt/second')
    expect(router.currentRoute.value.path).toBe('/ppt/first')
    expect(store.pending).toMatchObject({ key: original[3], revision: 3 })
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
    expect(wrapper!.text()).toContain('重试原操作')
    expect(store.editable).toBe(false)
    expect(api.get.mock.calls.every(([id]) => id === 'first')).toBe(true)

    api.operations.mockResolvedValueOnce({ revision: 3, deck: pptDeck(), createdIds: {} })
    await act(async () => {
      await wrapper!.get('.ppt-page-notice button:last-child').trigger('click')
      await flushPromises()
    })
    expect(api.operations.mock.calls[1]).toEqual(original)
    expect(store.pending).toBeNull()
    expect(wrapper!.find('.ppt-page-notice').exists()).toBe(false)
    await navigate('/ppt')
    await navigate('/ppt/first')
    await selectText()
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('vue')
    expect(api.operations).toHaveBeenCalledTimes(2)
  })

  it('exposes a restored pending retry immediately and pauses restored property autosave', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const element = { ...pptText(), text: '等待回执的草稿' }
    sessionStorage.setItem('loopper.ppt.pending.first', JSON.stringify({
      kind: 'operations', key: 'original-key', revision: 3,
      payload: { operations: [{ op: 'update_element', slideId: 'slide-1', elementId: 'text-1', patch: { text: element.text } }] },
    }))
    sessionStorage.setItem('loopper.ppt.element.first.text-1', JSON.stringify({ draft: element, baseline: JSON.stringify(pptText()), revision: 3 }))
    await open()
    expect(wrapper!.text()).toContain('重试原操作')
    expect(api.operations).not.toHaveBeenCalled()
    await selectText()
    expect((wrapper!.get('.ppt-properties textarea').element as HTMLTextAreaElement).value).toBe(element.text)
    expect(wrapper!.get('.ppt-properties fieldset').attributes('disabled')).toBeDefined()
    await act(async () => { await vi.advanceTimersByTimeAsync(1200) })
    expect(api.operations).not.toHaveBeenCalled()
    await navigate('/ppt')
    expect(router.currentRoute.value.path).toBe('/ppt/first')
    expect(usePptStore().pending?.key).toBe('original-key')
  })

  it('keeps the original confirmation semantics for an ordinary unsaved property draft', async () => {
    await open()
    await selectText()
    await act(async () => { await wrapper!.get('.ppt-properties input[type="number"][min="1"]').setValue('0') })
    expect(usePptStore().pending).toBeNull()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    await navigate('/ppt')
    expect(router.currentRoute.value.path).toBe('/ppt/first')
    expect(wrapper!.find('.ppt-properties').exists()).toBe(true)
    await navigate('/ppt')
    expect(router.currentRoute.value.path).toBe('/ppt')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(JSON.parse(sessionStorage.getItem('loopper.ppt.element.first.text-1')!).draft.width).toBe(0)
    expect(api.operations).not.toHaveBeenCalled()
  })
})
