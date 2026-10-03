import type { ThemeConfig } from 'antd'
import { theme } from 'antd'
import type { SkinDefinition } from '@/themes/types'

function pixels(value: string): number {
  if (!/^\d+(?:\.\d+)?px$/.test(value)) throw new Error('基础组件圆角必须是非负px')
  return Number.parseFloat(value)
}

/** Pure adapter. Uses existing skin definitions; no theme-specific business branches. */
export function foundationTheme(skin: SkinDefinition, reducedMotion = false): ThemeConfig {
  const c = skin.colors
  return {
    algorithm: skin.colorScheme === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: c.primary, colorLink: c.link, colorText: c.text, colorTextSecondary: c.secondary,
      colorTextTertiary: c.muted, colorBgLayout: c.canvas, colorBgContainer: c.canvas,
      colorBgElevated: c.elevated, colorBorder: c.border, colorBorderSecondary: c.borderMuted,
      colorSuccess: c.success, colorWarning: c.warning, colorError: c.danger,
      colorTextLightSolid: c.onEmphasis, fontFamily: skin.fonts.ui, fontSize: 14,
      borderRadius: pixels(skin.radii.control), borderRadiusLG: pixels(skin.radii.card),
      // Keep Ant's public motion token stable: true->false changes its provider tree.
      // Zero durations and scoped reduced-motion CSS preserve focus and owner leases.
      controlHeight: 36, motion: true, motionDurationFast: reducedMotion ? '0s' : '.12s',
      motionDurationMid: reducedMotion ? '0s' : '.18s', motionDurationSlow: reducedMotion ? '0s' : '.18s',
    },
    components: {
      Button: { primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none' },
      Table: { headerBg: c.surface, rowHoverBg: c.hover, rowSelectedBg: c.surface },
      Input: { activeShadow: 'none', hoverBorderColor: c.link },
      Modal: { contentBg: c.elevated, headerBg: c.elevated, titleColor: c.text },
    },
  }
}
