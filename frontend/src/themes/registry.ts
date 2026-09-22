import { githubWhite } from './githubWhite'
import { techBlue } from './techBlue'
import type { SkinDefinition } from './types'

export const SKIN_STORAGE_KEY = 'loopper.skin'
export const DEFAULT_SKIN_ID = techBlue.id
export const skins: readonly SkinDefinition[] = [techBlue, githubWhite]

if (new Set(skins.map(skin => skin.id)).size !== skins.length || skins.some(skin => !/^[a-z][a-z0-9-]*$/.test(skin.id))) {
  throw new Error('皮肤 ID 必须唯一，并使用小写字母、数字和连字符')
}

export function resolveSkin(id: unknown): SkinDefinition {
  return skins.find(skin => skin.id === id) ?? techBlue
}
