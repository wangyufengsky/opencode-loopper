import { appearanceDefaults } from './appearance'
import { shadeCatalog } from './shades'
import { rendererColors, rendererColor } from './renderers'
import { DEFAULT_SKIN_ID, SKIN_STORAGE_KEY, skins } from './registry'
import type { SkinDefinition } from './types'

export function rgbChannels(color: string): string {
  if (!/^#[\da-f]{6}$/i.test(color)) throw new Error(`皮肤基础颜色必须使用六位十六进制值：${color}`)
  return [1, 3, 5].map(offset => Number.parseInt(color.slice(offset, offset + 2), 16)).join(' ')
}

export function skinVariables(skin: SkinDefinition): Record<string, string> {
  const c = skin.colors
  const vars: Record<string, string> = {
    '--color-bg-canvas': c.canvas, '--color-bg-surface': c.surface, '--color-bg-elevated': c.elevated,
    '--color-bg-hover': c.hover, '--color-border-default': c.border, '--color-border-muted': c.borderMuted,
    '--color-text-primary': c.text, '--color-text-secondary': c.secondary, '--color-text-muted': c.muted,
    '--color-text-tertiary': c.muted, '--color-action-primary': c.primary, '--color-link': c.link,
    '--color-accent-cyan': c.cyan, '--color-accent-ai': c.ai, '--color-success': c.success,
    '--color-session-warning': c.warning, '--color-task-danger': c.danger, '--color-on-emphasis': c.onEmphasis,
    '--font-ui': skin.fonts.ui, '--font-code': skin.fonts.code,
    '--radius-control': skin.radii.control, '--radius-card': skin.radii.card, '--radius-dialog': skin.radii.dialog,
    '--shadow-card': skin.shadows.card, '--shadow-glow': skin.shadows.glow,
    '--skin-artwork-display': skin.artworkDisplay, '--skin-artwork-right': skin.artworkRight ?? '-4%',
    '--skin-decoration-opacity': skin.decorationOpacity,
  }
  for (const [key, value] of Object.entries(skin.primaryButton)) {
    vars[`--skin-primary-${key.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`)}`] = value
  }
  for (const [key, shade] of Object.entries(shadeCatalog)) {
    const value = skin.shades?.[key] ?? c[shade.role]
    vars[`--shade-${key}`] = value
    vars[`--shade-${key}-rgb`] = rgbChannels(value)
  }
  for (const key of Object.keys(rendererColors) as Array<keyof typeof rendererColors>) {
    const value = rendererColor(skin, key)
    vars[`--renderer-${key}`] = value
    vars[`--renderer-${key}-rgb`] = rgbChannels(value)
  }
  for (const key of Object.keys(skin.appearance ?? {})) {
    if (!(key in appearanceDefaults)) throw new Error(`未知皮肤外观配置：${key}`)
  }
  for (const [key, value] of Object.entries({ ...appearanceDefaults, ...skin.appearance })) {
    vars[`--appearance-${key}`] = value
  }
  return vars
}

/** Shared by Vite development and production: skins exist before the app's first paint. */
export function skinStyles(): string {
  return skins.map(skin => {
    const selector = `${skin.id === DEFAULT_SKIN_ID ? ':root,' : ''}html[data-skin="${skin.id}"]`
    return `${selector}{color-scheme:${skin.colorScheme};background:${skin.colors.canvas};color:${skin.colors.text};${Object.entries(skinVariables(skin)).map(([key, value]) => `${key}:${value}`).join(';')}}`
  }).join('\n')
}

export function skinBootstrap(): string {
  const options = skins.map(({ id, colorScheme, colors }) => ({ id, colorScheme, canvas: colors.canvas }))
  return `(()=>{const skins=${JSON.stringify(options)};let id=${JSON.stringify(DEFAULT_SKIN_ID)};try{id=localStorage.getItem(${JSON.stringify(SKIN_STORAGE_KEY)})||id}catch{}const skin=skins.find(s=>s.id===id)||skins.find(s=>s.id===${JSON.stringify(DEFAULT_SKIN_ID)});document.documentElement.dataset.skin=skin.id;document.querySelector('meta[name="theme-color"]')?.setAttribute('content',skin.canvas)})();`
}
