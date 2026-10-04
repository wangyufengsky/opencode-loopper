import { readFileSync } from 'node:fs'
import type { UiSemanticKey } from '../../src/foundation/semanticRegistry'

// Read the same central catalogue in Node without executing the browser icon module.
const catalogue = JSON.parse(readFileSync(new URL('../../src/foundation/semantic-registry-data.json', import.meta.url), 'utf8'))
export function semanticName(key: UiSemanticKey, target?: string): string {
  const entry = catalogue.actions[key] ?? catalogue.objects[key]
  if (!entry) throw new Error(`Unregistered E2E semantic: ${key}`)
  return target ? `${entry.name}：${target}` : entry.name
}
