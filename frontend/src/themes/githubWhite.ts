import type { SkinDefinition } from './types'

/** GitHub Primer-inspired light palette, packaged locally with the application. */
export const githubWhite: SkinDefinition = {
  id: 'github-white', label: 'GitHub 白', colorScheme: 'light',
  colors: {
    canvas: '#ffffff', surface: '#f6f8fa', elevated: '#ffffff', hover: '#eff2f5',
    border: '#d1d9e0', borderMuted: '#e6eaef', text: '#1f2328', secondary: '#59636e', muted: '#656d76',
    primary: '#1f883d', link: '#0969da', cyan: '#0969da', ai: '#8250df',
    success: '#1a7f37', warning: '#9a6700', danger: '#cf222e', onEmphasis: '#ffffff', shadow: '#1f2328',
  },
  fonts: {
    ui: '-apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", Helvetica, Arial, sans-serif',
    code: 'ui-monospace, "SFMono-Regular", "SF Mono", Menlo, Consolas, monospace',
  },
  radii: { control: '6px', card: '6px', dialog: '12px' },
  shadows: { card: '0 1px 0 rgb(31 35 40 / 4%)', glow: 'none' },
  artworkDisplay: 'none', decorationOpacity: '0',
  appearance: {
    'app-app-main-background': 'var(--color-bg-canvas)',
    'app-card-background': 'var(--color-bg-canvas)',
    'app-metric-card-background': 'var(--color-bg-canvas)',
    'app-brand-mark-background': 'var(--color-bg-surface)',
    'app-nav-item-router-link-active-background': 'var(--color-bg-hover)',
    'home-view-home-workspace-link-background': 'var(--color-bg-canvas)',
    'home-view-home-hero-after-background': 'none',
  },
  primaryButton: {
    text: '#ffffff', background: '#1f883d', border: '#1f883d',
    hoverText: '#ffffff', hoverBackground: '#1a7f37', hoverBorder: '#1a7f37',
    activeText: '#ffffff', activeBackground: '#166534', activeBorder: '#166534',
  },
}
