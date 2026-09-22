import { techBlueAppearance } from './appearance'
import { shadeCatalog } from './shades'
import { rendererColors } from './renderers'
import type { SkinDefinition } from './types'

export const techBlue: SkinDefinition = {
  id: 'tech-blue', label: '科技蓝', colorScheme: 'dark',
  colors: {
    canvas: '#070b14', surface: '#0d1424', elevated: '#121c30', hover: '#16233a',
    border: '#21304b', borderMuted: '#1a2942', text: '#e6edf8', secondary: '#9aa8bd', muted: '#65738a',
    primary: '#3b82f6', link: '#7dd3fc', cyan: '#22d3ee', ai: '#8b5cf6',
    success: '#22c55e', warning: '#f59e0b', danger: '#ef4444', onEmphasis: '#ffffff', shadow: '#000000',
  },
  fonts: {
    ui: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    code: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace',
  },
  radii: { control: '6px', card: '10px', dialog: '14px' },
  shadows: { card: '0 12px 32px rgb(0 0 0 / 20%)', glow: '0 0 0 1px rgb(34 211 238 / 18%), 0 0 28px rgb(34 211 238 / 9%)' },
  shades: {
    ...Object.fromEntries(Object.entries(shadeCatalog).map(([key, shade]) => [key, shade.original])),
    ...Object.fromEntries(Object.entries(rendererColors).map(([key, shade]) => [`renderer-${key}`, shade.original])),
  },
  appearance: techBlueAppearance,
  artworkDisplay: 'block', decorationOpacity: '.16',
  primaryButton: {
    text: '#bfdbfe', background: 'rgb(59 130 246 / 15%)', border: 'rgb(59 130 246 / 48%)',
    hoverText: '#eff6ff', hoverBackground: 'rgb(59 130 246 / 27%)', hoverBorder: 'rgb(96 165 250 / 76%)',
    activeText: '#eff6ff', activeBackground: 'rgb(37 99 235 / 22%)', activeBorder: '#60a5fa',
  },
}
