import catalogue from './semantic-registry-data.json'
import localGlyphs from './semantic-glyphs.json'

export type UiObjectKey = keyof typeof catalogue.objects
export type UiActionKey = keyof typeof catalogue.actions
export type UiSemanticKey = UiObjectKey | UiActionKey

function freeze<T>(value: T): Readonly<T> {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) freeze(child)
    Object.freeze(value)
  }
  return value
}

/** One central vocabulary; catalogue metadata never grants server permission. */
export const uiSemantics = freeze(catalogue)

export function semanticEntry(key: UiSemanticKey) {
  if (Object.prototype.hasOwnProperty.call(uiSemantics.objects, key)) return uiSemantics.objects[key as UiObjectKey]
  if (Object.prototype.hasOwnProperty.call(uiSemantics.actions, key)) return uiSemantics.actions[key as UiActionKey]
  throw new Error(`未登记的界面语义：${key}`)
}
export function semanticLabel(key: UiSemanticKey): string { return semanticEntry(key).label }
export function semanticName(key: UiSemanticKey, target?: string): string {
  return target ? `${semanticEntry(key).name}：${target}` : semanticEntry(key).name
}
export function semanticGlyph(key: UiSemanticKey) {
  const name = semanticEntry(key).icon
  const glyph = localGlyphs.glyphs[name as keyof typeof localGlyphs.glyphs]
  if (!glyph) throw new Error(`语义图标不在锁定本地Lucide中：${key}`)
  return glyph
}

export { SemanticIcon } from './SemanticIcon'
