import { semanticGlyph, type UiSemanticKey } from './semanticRegistry'

/** Local trusted Lucide only; the owning control provides its accessible name. */
export function SemanticIcon({ semanticKey, size = 18, className }: {
  semanticKey: UiSemanticKey; size?: number; className?: string
}) {
  const glyph = semanticGlyph(semanticKey)
  return <svg xmlns="http://www.w3.org/2000/svg" width={size} height={size}
    viewBox={`0 0 ${glyph.width} ${glyph.height}`} aria-hidden="true" focusable="false"
    data-semantic-icon={semanticKey} className={className}
    dangerouslySetInnerHTML={{ __html: glyph.body }} />
}
