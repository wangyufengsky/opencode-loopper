import { rendererColor } from './renderers'
import type { SkinDefinition } from './types'

let loader: Promise<typeof import('mermaid')['default']> | undefined
let queue: Promise<unknown> = Promise.resolve()
let sequence = 0

export function nextDiagramId(): string { return `loopper-mermaid-${++sequence}` }

/** Mermaid's configuration is global. Initialize + render must share one serialized theme snapshot. */
export function renderDiagram(id: string, source: string, skin: SkinDefinition) {
  const run = queue.then(async () => {
    loader ??= import('mermaid').then(module => module.default).catch(error => { loader = undefined; throw error })
    const mermaid = await loader
    mermaid.initialize({
      startOnLoad: false, suppressErrorRendering: true, securityLevel: 'strict',
      theme: skin.colorScheme === 'dark' ? 'dark' : 'base',
      flowchart: { htmlLabels: false },
      themeVariables: {
        darkMode: skin.colorScheme === 'dark',
        background: rendererColor(skin, 'diagram'),
        primaryColor: rendererColor(skin, 'diagramPrimary'),
        primaryTextColor: rendererColor(skin, 'diagramText'),
        primaryBorderColor: rendererColor(skin, 'diagramBorder'),
        lineColor: rendererColor(skin, 'diagramLine'),
        secondaryColor: rendererColor(skin, 'diagramSecondary'),
        tertiaryColor: rendererColor(skin, 'diagramTertiary'),
        fontFamily: skin.fonts.ui,
      },
    })
    return mermaid.render(id, source)
  })
  queue = run.catch(() => undefined)
  return run
}
