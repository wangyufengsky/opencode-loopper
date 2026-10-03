import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { uiSemantics } from './semanticRegistry'

describe('W1 ownership boundary', () => {
  it('central runtime vocabulary equals the frozen desktop contract; no per-page icon catalogue', () => {
    const design = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/prototype/desktop-v2/semantic-registry.json'), 'utf8'))
    for (const key of ['objects', 'actions', 'guards', 'components', 'routes'] as const) expect(uiSemantics[key]).toEqual(design[key])
  })
  it('foundation does not import Vue, issue transport commands, or create another history owner', () => {
    const directory = resolve(process.cwd(), 'src/foundation')
    function inspect(path: string): void {
      for (const entry of readdirSync(path, { withFileTypes: true })) {
        const name = join(path, entry.name)
        if (entry.isDirectory()) { inspect(name); continue }
        if (!/\.tsx?$/.test(name) || /\.spec\.tsx?$/.test(name)) continue
        const source = readFileSync(name, 'utf8')
        expect(source, entry.name).not.toMatch(/from\s+['"](?:vue|pinia|vue-router|@iconify\/vue)|from\s+['"][^'"]*\.vue['"]|createBrowserRouter|history\.(?:pushState|replaceState)|fetch\s*\(/)
        if (!['SemanticIcon.tsx', 'semanticRegistry.ts'].includes(entry.name)) {
          expect(source, entry.name).not.toMatch(/@iconify-json|lucide:|<svg\b|dangerouslySetInnerHTML/)
        }
      }
    }
    inspect(directory)
  })
})
