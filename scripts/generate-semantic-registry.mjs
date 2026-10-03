import { readFileSync, writeFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'
const root = new URL('../', import.meta.url)
const read = name => JSON.parse(readFileSync(new URL(name, root), 'utf8'))
const base = read('docs/design/react-full-migration/prototype/desktop-v2/semantic-registry.json')
const additional = { objects: {}, actions: {} }
for (const wave of ['w2', 'w3']) for (const section of ['objects', 'actions']) {
  for (const [key, entry] of Object.entries(read(`docs/design/react-full-migration/semantic-${wave}-additions.json`)[section])) {
    assert(!(key in base[section]) && !(key in additional[section]), `禁止覆盖已冻结语义：${key}`)
    additional[section][key] = entry
  }
}
const generated = Object.fromEntries(['schemaVersion', 'objects', 'actions', 'guards', 'components', 'routes'].map(key => [key,
  key === 'objects' || key === 'actions' ? { ...base[key], ...additional[key] } : base[key]]))
const labels = new Map()
for (const entry of [...Object.values(generated.objects), ...Object.values(generated.actions)]) {
  if (labels.has(entry.label)) assert.equal(entry.icon, labels.get(entry.label), `同名必须同图标：${entry.label}`)
  labels.set(entry.label, entry.icon)
}
const bytes = JSON.stringify(generated, null, 2) + '\n'
const target = new URL('frontend/src/foundation/semantic-registry-data.json', root)
if (process.argv.includes('--check')) assert.equal(readFileSync(target, 'utf8'), bytes, '中央语义表失配，请明确生成并审查')
else writeFileSync(target, bytes)
console.log(`中央语义表可复现：${Object.keys(generated.objects).length}对象/${Object.keys(generated.actions).length}动作，旧定义无覆盖`)
