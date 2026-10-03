import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { mermaidErrorMessage, mermaidPendingMessage, renderSafeMermaid, type SafeMermaidDiagram } from '@/domain/diagrams/mermaid'
import type { SkinDefinition } from '@/themes/types'

export type MermaidStatus = 'pending' | 'ready' | 'error'
export interface MermaidDiagramProps { source: string; skin: SkinDefinition; onStatus?: (status: MermaidStatus) => void; isCurrent?: () => boolean }
export function MermaidDiagram({ source, skin, onStatus, isCurrent }: MermaidDiagramProps) {
  const [result, setResult] = useState<{ status: MermaidStatus; diagram?: SafeMermaidDiagram }>({ status: 'pending' })
  const svgHost = useRef<HTMLDivElement>(null)
  const callback = useRef(onStatus)
  callback.current = onStatus
  useEffect(() => {
    let active = true
    setResult({ status: 'pending' })
    void renderSafeMermaid(source, skin).then(diagram => {
      if (active && (!isCurrent || isCurrent())) setResult({ status: 'ready', diagram })
    }, () => {
      if (active && (!isCurrent || isCurrent())) setResult({ status: 'error' })
    })
    return () => { active = false }
  }, [source, skin, isCurrent])
  useLayoutEffect(() => {
    try {
      if (result.diagram && svgHost.current) result.diagram.bindFunctions?.(svgHost.current)
    } catch {
      setResult({ status: 'error' })
      return
    }
    callback.current?.(result.status)
  }, [result])
  return <div className={`react-mermaid-diagram is-${result.status}`} data-canvas-runtime="react" data-canvas-kind="mermaid" aria-busy={result.status === 'pending'}>
    {result.status === 'ready' ? <div key={result.diagram!.id} ref={svgHost} className="react-mermaid-svg" dangerouslySetInnerHTML={{ __html: result.diagram!.svg }} />
      : <span role={result.status === 'error' ? 'status' : undefined}>{result.status === 'error' ? mermaidErrorMessage : mermaidPendingMessage}</span>}
  </div>
}
