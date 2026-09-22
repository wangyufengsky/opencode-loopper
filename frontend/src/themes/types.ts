/** Public skin interface. Layout and business state deliberately stay in components. */
export interface SkinColors {
  canvas: string
  surface: string
  elevated: string
  hover: string
  border: string
  borderMuted: string
  text: string
  secondary: string
  muted: string
  primary: string
  link: string
  cyan: string
  ai: string
  success: string
  warning: string
  danger: string
  onEmphasis: string
  shadow: string
}

export interface SkinDefinition {
  id: string
  label: string
  colorScheme: 'light' | 'dark'
  colors: SkinColors
  fonts: { ui: string; code: string }
  radii: { control: string; card: string; dialog: string }
  shadows: { card: string; glow: string }
  /** Component appearance overrides; all keys come from the shared appearance catalog. */
  appearance?: Record<string, string>
  /** Exact historical color shades, only needed to preserve an existing skin. */
  shades?: Record<string, string>
  /** Bundled image filename in src/assets; landscape 3:2 artwork. */
  homeArtwork: string
  artworkDisplay: 'block' | 'none'
  decorationOpacity: string
  primaryButton: { text: string; background: string; border: string; hoverText: string; hoverBackground: string; hoverBorder: string; activeText: string; activeBackground: string; activeBorder: string }
}
