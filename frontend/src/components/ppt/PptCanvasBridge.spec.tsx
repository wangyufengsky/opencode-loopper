import { act, fireEvent } from '@testing-library/react'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PptCanvas from './PptCanvas.vue'
import PptSlideNavigator from './PptSlideNavigator.vue'
import { pptDeck } from './pptTestFixtures'
import type { PptJob } from '@/types/domain'

const preference = vi.hoisted(() => ({ value: 'react' as 'react' | 'vue' }))
vi.mock('@/migration/canvasRuntimeVue', async () => {
  const { readonly, ref } = await import('vue')
  return { useCanvasRuntime: () => readonly(ref(preference.value)) }
})

let wrapper: VueWrapper | undefined
beforeEach(() => { preference.value = 'react' })
afterEach(() => { act(() => wrapper?.unmount()); wrapper = undefined })

function attach(create: () => VueWrapper): VueWrapper {
  let mounted!: VueWrapper
  act(() => { mounted = create() })
  return mounted
}

async function flushView() {
  await act(async () => { await nextTick() })
}

async function updateProps(props: Record<string, unknown>) {
  await act(async () => { await wrapper!.setProps(props) })
}

describe('PPT Vue to React bridge', () => {
  it('mounts the React canvas and forwards events and changed props through the existing Vue contract', async () => {
    const deck = pptDeck()
    wrapper = attach(() => mount(PptCanvas, { props: { deck, slide: deck.slides[0]!, selected: 'text-1', revision: 3 } }))
    await flushView()
    expect(wrapper.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
    fireEvent.keyDown(wrapper.get('.ppt-canvas-object').element, { key: 'ArrowRight', shiftKey: true })
    expect(wrapper.emitted('patch')?.[0]).toEqual(['text-1', { x: 90, y: 70 }, 3])
    fireEvent.click(wrapper.get('.ppt-canvas-object').element)
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['text-1'])
    await updateProps({ revision: 4, preview: '/new-preview.png', previewRevision: 4 })
    expect(wrapper.get('.ppt-preview-image').attributes('src')).toBe('/new-preview.png')
    fireEvent.keyDown(wrapper.get('.ppt-canvas-object').element, { key: 'Alt', altKey: true })
    fireEvent.keyDown(wrapper.get('.ppt-canvas-object').element, { key: 'ArrowRight', altKey: true })
    expect(wrapper.emitted('patch')?.at(-1)).toEqual(['text-1', { width: 401, height: 90 }, 4])
    fireEvent.keyDown(wrapper.get('.ppt-canvas-object').element, { key: 'Delete' })
    expect(wrapper.emitted('remove')?.at(-1)).toEqual(['text-1'])
    preference.value = 'vue'
    await updateProps({ revision: 5 })
    expect(wrapper.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('react')
  })

  it('keeps a Vue fallback with the same event contract', async () => {
    preference.value = 'vue'
    const deck = pptDeck()
    wrapper = attach(() => mount(PptCanvas, { props: { deck, slide: deck.slides[0]!, selected: 'text-1', revision: 3 } }))
    await flushView()
    expect(wrapper.get('[data-canvas-kind="ppt"]').attributes('data-canvas-runtime')).toBe('vue')
    await wrapper.get('.ppt-canvas-object').trigger('keydown', { key: 'ArrowRight', shiftKey: true })
    expect(wrapper.emitted('patch')?.[0]).toEqual(['text-1', { x: 90, y: 70 }, 3])
    await wrapper.get('.ppt-canvas-object').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('select')?.at(-1)).toEqual([''])
  })

  it('reflects an in-place Vue object update through a plain React DTO snapshot', async () => {
    const deck = reactive(pptDeck())
    wrapper = attach(() => mount(PptCanvas, { props: { deck, slide: deck.slides[0]!, selected: 'text-1', revision: 3 } }))
    await flushView()
    deck.slides[0]!.elements[0]!.text = '修改后的标题'
    deck.slides[0]!.elements[0]!.x = 240
    await flushView()
    const object = wrapper.get('.ppt-canvas-object')
    expect(object.text()).toBe('修改后的标题')
    expect(object.attributes('aria-label')).toBe('文本框：修改后的标题')
    expect((object.element as HTMLElement).style.left).toBe('25%')
    deck.slides[0]!.elements[0]!.locked = true
    await flushView()
    expect(wrapper.find('.ppt-resize-handle').exists()).toBe(false)
  })

  it('bridges revision-matched thumbnail previews and selection without creating a new request path', async () => {
    const deck = pptDeck()
    const jobs: PptJob[] = [{
      id: 'preview', documentId: 'doc', kind: 'PREVIEW', revision: 3, state: 'COMPLETED',
      completed: 1, total: 1, detail: '', createdAt: '2026-10-03T00:00:00Z',
      artifacts: [{ id: 'current', slideId: 'slide-1', name: 'current.png', mediaType: 'image/png', url: '' }],
    }]
    wrapper = attach(() => mount(PptSlideNavigator, { props: { documentId: 'doc', deck, jobs, selected: 'slide-1', revision: 3, manual: true } }))
    await flushView()
    expect(wrapper.get('[data-canvas-kind="ppt-navigator"]').attributes('data-canvas-runtime')).toBe('react')
    expect(wrapper.get('img').attributes('src')).toBe('/api/ppt/documents/doc/artifacts/current')
    fireEvent.click(wrapper.get('[aria-label="第 2 页：下一步计划"]').element)
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['slide-2'])
    fireEvent.click(wrapper.get('.ppt-add-page').element)
    expect(wrapper.emitted('add')).toHaveLength(1)
    await updateProps({ revision: 4 })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('uses the Vue thumbnail fallback when the route selects Vue', async () => {
    preference.value = 'vue'
    wrapper = attach(() => mount(PptSlideNavigator, { props: { documentId: 'doc', deck: pptDeck(), jobs: [], selected: 'slide-1', revision: 3 } }))
    await flushView()
    expect(wrapper.get('[data-canvas-kind="ppt-navigator"]').attributes('data-canvas-runtime')).toBe('vue')
    await wrapper.get('[aria-label="第 2 页：下一步计划"]').trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['slide-2'])
  })
})
