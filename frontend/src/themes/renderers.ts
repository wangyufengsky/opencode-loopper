import type { SkinDefinition } from './types'

export const rendererColors = {
  keyword: { original: '#c4b5fd', role: 'ai' },
  string: { original: '#86efac', role: 'success' },
  number: { original: '#f9c98b', role: 'warning' },
  type: { original: '#7dd3fc', role: 'link' },
  comment: { original: '#94a3b8', role: 'muted' },
  function: { original: '#93c5fd', role: 'link' },
  invalid: { original: '#fda4af', role: 'danger' },
  editor: { original: '#07101d', role: 'canvas' },
  editorText: { original: '#dbeafe', role: 'text' },
  gutter: { original: '#091321', role: 'surface' },
  gutterText: { original: '#5d6d86', role: 'muted' },
  active: { original: '#38bdf8', role: 'link' },
  evidence: { original: '#22d3ee', role: 'cyan' },
  changed: { original: '#22c55e', role: 'success' },
  changedBorder: { original: '#4ade80', role: 'success' },
  conflict: { original: '#f87171', role: 'danger' },
  conflictActive: { original: '#fb923c', role: 'warning' },
  selection: { original: '#3b82f6', role: 'link' },
  diagram: { original: '#0b1221', role: 'canvas' },
  diagramPrimary: { original: '#152440', role: 'surface' },
  diagramText: { original: '#e6edf8', role: 'text' },
  diagramBorder: { original: '#3b82f6', role: 'link' },
  diagramLine: { original: '#8293ad', role: 'secondary' },
  diagramSecondary: { original: '#182c3a', role: 'hover' },
  diagramTertiary: { original: '#171f35', role: 'surface' },
} as const

export function rendererColor(skin: SkinDefinition, key: keyof typeof rendererColors): string {
  return skin.shades?.[`renderer-${key}`] ?? skin.colors[rendererColors[key].role]
}
