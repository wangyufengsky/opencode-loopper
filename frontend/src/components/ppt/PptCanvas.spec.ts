import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PptCanvas from './PptCanvas.vue'
import { pptDeck } from './pptTestFixtures'
describe('PPT object manipulation', () => {
  it('translates scaled preview dragging to point geometry and retains its revision', async () => {
    const deck = pptDeck(),
      wrapper = mount(PptCanvas, {
        props: {
          deck,
          slide: deck.slides[0]!,
          selected: 'text-1',
          revision: 3,
        },
      })
    wrapper.get('.ppt-canvas').element.getBoundingClientRect = () =>
      ({
        width: 480,
      }) as DOMRect
    await wrapper.get('.ppt-canvas-object').trigger('pointerdown', {
      button: 0,
      clientX: 100,
      clientY: 100,
      pointerId: 1,
    })
    await wrapper.get('.ppt-canvas').trigger('pointermove', {
      clientX: 120,
      clientY: 110,
      pointerId: 1,
    })
    await wrapper.get('.ppt-canvas').trigger('pointerup', {
      pointerId: 1,
    })
    expect(wrapper.emitted('patch')?.[0]).toEqual([
      'text-1',
      {
        x: 120,
        y: 90,
        width: 400,
        height: 90,
      },
      3,
    ])
    wrapper.unmount()
  })
  it('supports keyboard movement but does not modify locked pages', async () => {
    const deck = pptDeck(),
      wrapper = mount(PptCanvas, {
        props: {
          deck,
          slide: deck.slides[0]!,
          selected: 'text-1',
          revision: 3,
        },
      })
    await wrapper.get('.ppt-canvas-object').trigger('keydown', {
      key: 'ArrowRight',
      shiftKey: true,
    })
    expect(wrapper.emitted('patch')?.[0]).toEqual([
      'text-1',
      {
        x: 90,
        y: 70,
      },
      3,
    ])
    await wrapper.setProps({
      slide: {
        ...deck.slides[0]!,
        locked: true,
      },
    })
    await wrapper.get('.ppt-canvas-object').trigger('keydown', {
      key: 'ArrowRight',
    })
    expect(wrapper.emitted('patch')).toHaveLength(1)
    wrapper.unmount()
  })
  it('identifies stale rendered output without claiming it shows the current draft', () => {
    const deck = pptDeck(),
      wrapper = mount(PptCanvas, {
        props: {
          deck,
          slide: deck.slides[0]!,
          selected: '',
          revision: 4,
          previewRevision: 2,
          preview: '/api/ppt/documents/doc/artifacts/preview',
        },
      })
    expect(wrapper.text()).toContain('预览来自版本 2')
    expect(wrapper.text()).toContain('当前草稿为版本 4')
    wrapper.unmount()
  })
})
