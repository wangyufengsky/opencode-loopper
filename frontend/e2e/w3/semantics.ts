import { readFileSync } from 'node:fs'
// Playwright's Node runner does not apply Vite's JSON imports. Read the exact
// production catalogue bytes, retaining strict label/name checks on real DOM.
const catalogue = JSON.parse(readFileSync(new URL('../../src/foundation/semantic-registry-data.json', import.meta.url), 'utf8')) as { objects: Record<string, { name: string }>; actions: Record<string, { name: string }> }
export function semanticName(key: string, target?: string) {
  const entry = catalogue.actions[key] ?? catalogue.objects[key]
  if (!entry) throw new Error(`Missing production semantic key: ${key}`)
  return target ? `${entry.name}：${target}` : entry.name
}
