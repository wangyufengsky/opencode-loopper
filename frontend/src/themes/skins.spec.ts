import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { skinBootstrap, skinStyles, skinVariables } from './compile'
import { githubWhite } from './githubWhite'
import { techBlue } from './techBlue'
import { applySkin, currentSkin, initializeSkin } from './state'
import { SKIN_STORAGE_KEY } from './registry'
import type { SkinDefinition } from './types'

function contrast(a: string, b: string) {
  const luminance = (hex: string) => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    .reduce((n, v, i) => n + v * [0.2126, 0.7152, 0.0722][i]!, 0)
  const [x, y] = [luminance(a), luminance(b)].sort((a, b) => b - a)
  return (x! + .05) / (y! + .05)
}

afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); applySkin('tech-blue', false) })

describe('配置驱动皮肤', () => {
  it('第三套皮肤通过同一配置接口编译，无需修改页面或增加主题分支', () => {
    const third: SkinDefinition = { ...githubWhite, id: 'custom-paper', label: '纸张', colors: { ...githubWhite.colors, canvas: '#fff9ef', link: '#704020' }, radii: { control: '8px', card: '12px', dialog: '16px' } }
    const vars = skinVariables(third)
    expect(vars['--color-bg-canvas']).toBe('#fff9ef')
    expect(vars['--renderer-type']).toBe('#704020')
    expect(vars['--radius-control']).toBe('8px')
    expect(Object.keys(vars)).toEqual(Object.keys(skinVariables(techBlue)))
    expect(() => skinVariables({ ...third, appearance: { misspelled: 'none' } })).toThrow('未知皮肤外观配置')
  })

  it('首屏启动与运行时一致恢复、保存或兜底，并支持存储不可用', () => {
    document.head.innerHTML = '<meta name="theme-color" content="">'
    localStorage.setItem(SKIN_STORAGE_KEY, 'github-white')
    new Function(skinBootstrap())()
    expect(document.documentElement.dataset.skin).toBe('github-white')
    expect(document.querySelector('meta')?.content).toBe(githubWhite.colors.canvas)
    const stop = initializeSkin()
    expect(currentSkin.value.id).toBe('github-white')
    applySkin('tech-blue')
    expect(localStorage.getItem(SKIN_STORAGE_KEY)).toBe('tech-blue')
    applySkin('removed-skin')
    expect(currentSkin.value.id).toBe('tech-blue')
    stop()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied') })
    expect(() => new Function(skinBootstrap())()).not.toThrow()
    const stopUnavailable = initializeSkin()
    expect(() => applySkin('github-white')).not.toThrow()
    expect(currentSkin.value.id).toBe('github-white')
    stopUnavailable()
  })

  it('同源标签同步主题及清空操作，忽略其他配置和 sessionStorage', () => {
    const stop = initializeSkin()
    window.dispatchEvent(new StorageEvent('storage', { key: SKIN_STORAGE_KEY, newValue: 'github-white', storageArea: localStorage }))
    expect(currentSkin.value.id).toBe('github-white')
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', newValue: 'tech-blue' }))
    window.dispatchEvent(new StorageEvent('storage', { key: SKIN_STORAGE_KEY, newValue: 'tech-blue', storageArea: sessionStorage }))
    expect(currentSkin.value.id).toBe('github-white')
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(currentSkin.value.id).toBe('tech-blue')
    stop()
  })

  it('浅色正文、状态色和主按钮在主要背景上具有可读对比度', () => {
    for (const bg of ['canvas', 'surface', 'elevated'] as const) {
      for (const fg of ['text', 'secondary', 'muted', 'link', 'cyan', 'ai', 'success', 'warning', 'danger'] as const) {
        expect(contrast(githubWhite.colors[fg], githubWhite.colors[bg]), `${fg}/${bg}`).toBeGreaterThanOrEqual(4.5)
      }
    }
    expect(contrast(githubWhite.primaryButton.text, githubWhite.primaryButton.background)).toBeGreaterThanOrEqual(4.5)
    expect(skinStyles()).toContain('html[data-skin="github-white"]')
  })

  it('配色只能在皮肤配置中定义，阻止页面重新引入硬编码颜色', () => {
    const root = resolve(import.meta.dirname, '..')
    const scan = (directory: string): string[] => readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
      const path = resolve(directory, entry.name)
      if (entry.isDirectory()) return ['themes', 'mock', 'assets'].includes(entry.name) ? [] : scan(path)
      return /\.(vue|css|ts)$/.test(path) && !path.endsWith('.spec.ts') ? [path] : []
    })
    const offenders = scan(root).filter(path => /#[\da-f]{3,8}\b|\brgba?\(\s*\d/i.test(readFileSync(path, 'utf8')))
    expect(offenders).toEqual([])
  })
})
