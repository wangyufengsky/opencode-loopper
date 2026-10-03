import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'

const root = new URL('../', import.meta.url)
const read = path => readFileSync(new URL(path, root))
const locked = JSON.parse(read('frontend/package-lock.json')).packages['node_modules/@iconify-json/lucide']
const installed = JSON.parse(read('frontend/node_modules/@iconify-json/lucide/package.json'))
if (locked.version !== installed.version) throw new Error('Lucide安装版本与锁文件不一致')
const source = read('frontend/node_modules/@iconify-json/lucide/icons.json')
const lucide = JSON.parse(source)
const catalogue = JSON.parse(read('frontend/src/foundation/semantic-registry-data.json'))
const names = [...new Set([...Object.values(catalogue.objects), ...Object.values(catalogue.actions)].map(entry => entry.icon))].sort()
const glyphs = Object.fromEntries(names.map(name => {
  let canonical = name
  const visited = new Set()
  while (!lucide.icons[canonical]) {
    const alias = lucide.aliases?.[canonical]
    if (!alias || visited.has(canonical) || alias.rotate || alias.hFlip || alias.vFlip) throw new Error(`不支持的Lucide别名：${name}`)
    visited.add(canonical); canonical = alias.parent
  }
  const glyph = lucide.icons[canonical]
  return [name, { body: glyph.body, width: glyph.width ?? lucide.width, height: glyph.height ?? lucide.height }]
}))
const generated = JSON.stringify({ source: { package: '@iconify-json/lucide', version: locked.version,
  integrity: locked.integrity, iconsSha256: createHash('sha256').update(source).digest('hex') }, glyphs }, null, 2) + '\n'
const path = new URL('frontend/src/foundation/semantic-glyphs.json', root)
if (process.argv.includes('--check')) {
  if (readFileSync(path, 'utf8') !== generated) throw new Error('语义图标子集与锁定本地资源/语义表不一致，请显式重新生成并审查')
} else writeFileSync(path, generated)
console.log(`${names.length}本地Lucide语义图标子集${process.argv.includes('--check') ? '可复现' : '已生成'}：${fileURLToPath(path)}`)
