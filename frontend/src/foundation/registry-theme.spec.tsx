import { describe, expect, it, vi } from 'vitest'
import { act, render, cleanup, fireEvent } from '@testing-library/react'
import { Input } from 'antd'
import { StrictMode, useEffect } from 'react'
import { skins } from '@/themes/registry'
import { skinVariables } from '@/themes/compile'
import { foundationTheme } from './theme'
import { uiSemantics, semanticGlyph, semanticLabel, semanticName, SemanticIcon, type UiSemanticKey } from './semanticRegistry'
import { FoundationProvider } from './provider'

describe('W1 central vocabulary and theme', () => {
  it('all registered objects and actions resolve to local Lucide and Chinese accessible names', () => {
    for (const key of [...Object.keys(uiSemantics.objects), ...Object.keys(uiSemantics.actions)] as UiSemanticKey[]) {
      expect(semanticLabel(key)).toMatch(/[\u4e00-\u9fff]/)
      expect(semanticName(key)).toMatch(/[\u4e00-\u9fff]/)
      expect(semanticGlyph(key).body).not.toMatch(/https?:|<script|onload=/)
      expect(semanticGlyph(key).body).toContain('<')
    }
    expect(semanticName('ui.close', '节点详情')).toBe('关闭面板：节点详情')
    expect(() => semanticLabel('unregistered' as UiSemanticKey)).toThrow('未登记')
  })
  it('same object label maps to the same glyph and every current route is catalogued', () => {
    const glyphs = new Map<string, string>()
    for (const entry of [...Object.values(uiSemantics.objects), ...Object.values(uiSemantics.actions)]) {
      if (glyphs.has(entry.label)) expect(entry.icon).toBe(glyphs.get(entry.label))
      glyphs.set(entry.label, entry.icon)
    }
    expect(uiSemantics.routes).toHaveLength(31)
    expect(uiSemantics.actions['ui.close'].intent).not.toBe(uiSemantics.actions['task.cancel'].intent)
    expect(uiSemantics.actions['ui.close'].icon).not.toBe(uiSemantics.actions['task.cancel'].icon)
    expect(Object.isFrozen(uiSemantics.actions['ui.close'])).toBe(true)
  })
  it.each(skins)('$id adapts existing tokens without changing semantics or inventing a theme domain', skin => {
    const config = foundationTheme(skin)
    expect(config.token?.colorPrimary).toBe(skin.colors.primary)
    expect(config.token?.colorBgContainer).toBe(skin.colors.canvas)
    expect(config.token?.fontFamily).toBe(skin.fonts.ui)
    expect(config.components?.Table?.rowHoverBg).toBe(skin.colors.hover)
    expect(foundationTheme(skin, true).token?.motion).toBe(config.token?.motion)
    expect(foundationTheme(skin, true).token?.motionDurationFast).toBe('0s')
    expect(foundationTheme(skin, true).token?.motionDurationMid).toBe('0s')
  })
  it('theme change preserves the mounted consumer and decorative icon; mount/effects never command', () => {
    const mounted = vi.fn(), unmounted = vi.fn()
    function Consumer() { useEffect(() => { mounted(); return unmounted }, []); return <SemanticIcon semanticKey="ui.save" /> }
    const view = render(<FoundationProvider skin={skins[0]!} reducedMotion={false}><Consumer /></FoundationProvider>)
    const icon = view.container.querySelector('svg')!
    expect(icon.getAttribute('aria-hidden')).toBe('true')
    expect(icon.getAttribute('focusable')).toBe('false')
    view.rerender(<FoundationProvider skin={skins[1]!} reducedMotion={false}><Consumer /></FoundationProvider>)
    expect(view.container.querySelector('svg')).toBe(icon)
    expect(mounted).toHaveBeenCalledTimes(1)
    expect(unmounted).not.toHaveBeenCalled()
    const host = view.container.firstElementChild as HTMLElement
    expect(host.style.getPropertyValue('--skin-primary-background')).toBe(skinVariables(skins[1]!)['--skin-primary-background'])
    view.unmount(); expect(unmounted).toHaveBeenCalledTimes(1)
  })
  it('StrictMode owns and removes exactly its reduced-motion subscription', () => {
    const listeners = new Set<EventListenerOrEventListenerObject>()
    const media = { matches: false, addEventListener: (_: string, listener: EventListenerOrEventListenerObject) => listeners.add(listener), removeEventListener: (_: string, listener: EventListenerOrEventListenerObject) => listeners.delete(listener) }
    vi.stubGlobal('matchMedia', () => media)
    const view = render(<StrictMode><FoundationProvider skin={skins[0]!}><p>模拟内容</p></FoundationProvider></StrictMode>)
    expect(listeners.size).toBe(1)
    act(() => { media.matches = true; for (const listener of listeners) (listener as () => void)() })
    expect(view.container.querySelector('[data-reduced-motion="true"]')).not.toBeNull()
    view.unmount(); expect(listeners.size).toBe(0)
    vi.unstubAllGlobals(); cleanup()
  })
  it('changing reduced motion preserves the real Ant field DOM, focus, value and consumer lease', () => {
    const mounted = vi.fn(), unmounted = vi.fn()
    function Consumer() { useEffect(() => { mounted(); return unmounted }, []); return <Input aria-label="保留草稿" defaultValue="原草稿" /> }
    const view = render(<FoundationProvider skin={skins[0]!} reducedMotion={false}><Consumer /></FoundationProvider>)
    const input = view.getByRole('textbox', { name: '保留草稿' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: '未保存草稿' } })
    act(() => { input.focus(); input.setSelectionRange(1, 3) })
    view.rerender(<FoundationProvider skin={skins[0]!} reducedMotion><Consumer /></FoundationProvider>)
    expect(view.getByRole('textbox', { name: '保留草稿' })).toBe(input)
    expect(document.activeElement).toBe(input); expect(input.value).toBe('未保存草稿')
    expect([input.selectionStart, input.selectionEnd]).toEqual([1, 3])
    expect(mounted).toHaveBeenCalledTimes(1); expect(unmounted).not.toHaveBeenCalled()
    expect(view.container.querySelector('[data-reduced-motion="true"]')).not.toBeNull()
    view.rerender(<FoundationProvider skin={skins[0]!} reducedMotion={false}><Consumer /></FoundationProvider>)
    expect(document.activeElement).toBe(input); expect(mounted).toHaveBeenCalledTimes(1)
    view.unmount(); expect(unmounted).toHaveBeenCalledTimes(1)
  })
})
