import type { SkinDefinition } from './types'

/** Blue, red and white inspired by spdb.com.cn; UI shades tuned for readability. */
export const spdb: SkinDefinition = {
  id: 'spdb', label: 'spdb风', colorScheme: 'light',
  colors: {
    canvas: '#ffffff', surface: '#f3f6fc', elevated: '#ffffff', hover: '#e9eef9',
    border: '#cbd4e6', borderMuted: '#e2e7f0', text: '#1c2745', secondary: '#4e5d78', muted: '#626e84',
    primary: '#001e8c', link: '#004790', cyan: '#c51c2b', ai: '#5b4aa0',
    success: '#166534', warning: '#865e00', danger: '#b42332', onEmphasis: '#ffffff', shadow: '#001e50',
  },
  fonts: {
    ui: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
    code: 'ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace',
  },
  radii: { control: '6px', card: '10px', dialog: '12px' },
  shadows: { card: '0 6px 20px rgb(0 30 80 / 6%)', glow: '0 0 0 1px rgb(0 30 140 / 10%)' },
  homeArtwork: 'home-spdb.png', artworkRight: '0%', artworkDisplay: 'block', decorationOpacity: '.04',
  appearance: {
    'app-app-main-background': 'var(--color-bg-canvas)',
    'app-card-background': 'var(--color-bg-canvas)',
    'app-metric-card-background': 'var(--color-bg-canvas)',
    'app-brand-mark-background': 'var(--color-bg-canvas)',
    'app-nav-item-router-link-active-background': 'linear-gradient(90deg, rgb(0 30 140 / 9%), rgb(0 30 140 / 3%))',
    'home-view-home-workspace-link-background': 'linear-gradient(160deg, var(--color-bg-canvas), var(--color-bg-surface))',
    'home-view-home-hero-after-background': 'none',
  },
  primaryButton: {
    text: '#ffffff', background: '#001e8c', border: '#001e8c',
    hoverText: '#ffffff', hoverBackground: '#004790', hoverBorder: '#004790',
    activeText: '#ffffff', activeBackground: '#000073', activeBorder: '#000073',
  },
}
