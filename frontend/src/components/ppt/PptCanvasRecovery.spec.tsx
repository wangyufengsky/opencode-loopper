import { act, fireEvent } from '@testing-library/react'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PptCanvas from './PptCanvas.vue'
import { pptAgent, pptCapabilities, pptDeck, pptDocument, pptPlan } from './pptTestFixtures'
import { pptApi } from '@/api/ppt'
import { usePptStore } from '@/stores/pptStore'

const preference = vi.hoisted(() => ({ value: 'react' as 'react' | 'vue' }))
vi.mock('@/migration/canvasRuntimeVue', async () => {
  const { readonly, ref } = await import('vue')
  return { useCanvasRuntime: () => readonly(ref(preference.value)) }
})
vi.mock('@/api/ppt', () => ({
  pptApi: {
    get: vi.fn(), deck: vi.fn(), plan: vi.fn(), sources: vi.fn(), jobs: vi.fn(),
    revisions: vi.fn(), messages: vi.fn(), agent: vi.fn(), generation: vi.fn(),
    capabilities: vi.fn(), events: vi.fn(), operations: vi.fn(),
  },
}))
const api = vi.mocked(pptApi)
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  preference.value = 'react'
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.stubGlobal('crypto', webcrypto)
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('PPT canvas recovery through the authoritative store', () => {
  it('retains an unknown keyboard operation without a property draft across another document and Vue re-entry', async () => {
    const store = usePptStore()
    await store.load('first')
    let changed: Promise<boolean> | undefined
    act(() => {
      wrapper = mount(PptCanvas, {
        props: { deck: store.deck!, slide: store.deck!.slides[0]!, selected: 'text-1', revision: 3 },
        attrs: { onPatch: (id: string, patch: Record<string, unknown>, revision: number) => {
          changed = store.operations([{ op: 'update_element', slideId: 'slide-1', elementId: id, patch }], revision)
        } },
      })
    })
    await act(async () => { await nextTick() })
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
    expect(api.operations).not.toHaveBeenCalled()
    api.operations.mockRejectedValueOnce(new Error('network receipt lost'))
    fireEvent.keyDown(wrapper!.get('.ppt-canvas-object').element, { key: 'ArrowRight' })
    await changed
    const original = api.operations.mock.calls[0]!
    expect(original).toEqual(['first', 3, [{ op: 'update_element', slideId: 'slide-1', elementId: 'text-1', patch: { x: 81, y: 70 } }], expect.any(String)])
    const pending = sessionStorage.getItem('loopper.ppt.pending.first')
    expect(JSON.parse(pending!)).toMatchObject({ key: original[3], revision: 3, kind: 'operations' })
    expect(sessionStorage.getItem('loopper.ppt.element.first.text-1')).toBeNull()

    preference.value = 'vue'
    await act(async () => { await wrapper!.setProps({ editing: true }) })
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
    act(() => wrapper!.unmount())
    wrapper = undefined
    store.close()
    await store.load('second')
    expect(store.document?.id).toBe('second')
    expect(store.pending).toBeNull()
    expect(sessionStorage.getItem('loopper.ppt.pending.first')).toBe(pending)
    await store.load('first')
    expect(store.pending).toEqual(JSON.parse(pending!))
    act(() => {
      wrapper = mount(PptCanvas, {
        props: { deck: store.deck!, slide: store.deck!.slides[0]!, selected: 'text-1', revision: 3 },
      })
    })
    await act(async () => { await nextTick() })
    expect(wrapper!.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('vue')
    expect(api.operations).toHaveBeenCalledTimes(1)

    const accepted = pptDeck()
    accepted.slides[0]!.elements[0]!.x = 81
    api.operations.mockResolvedValueOnce({ revision: 4, deck: accepted, createdIds: {} })
    api.get.mockImplementation(async id => ({ ...pptDocument(id), revision: 4 }))
    api.plan.mockResolvedValue({ plan: pptPlan(), revision: 4 })
    api.deck.mockResolvedValue(accepted)
    expect(await store.retryPending()).toBe(true)
    expect(api.operations.mock.calls[1]).toEqual(original)
    expect(store.deck!.slides[0]!.elements[0]!.x).toBe(81)
    expect(store.document?.revision).toBe(4)
    expect(store.pending).toBeNull()
    expect(sessionStorage.getItem('loopper.ppt.pending.first')).toBeNull()
  })

})
