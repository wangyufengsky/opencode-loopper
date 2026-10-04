import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { uiSemantics } from './semanticRegistry'

describe('W1 ownership boundary', () => {
  it('central runtime vocabulary equals the frozen desktop contract plus audited wave additions; no per-page icon catalogue', () => {
    const design = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/prototype/desktop-v2/semantic-registry.json'), 'utf8'))
    const additions = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w2-additions.json'), 'utf8'))
    const w3 = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w3-additions.json'), 'utf8'))
    const w4 = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w4-additions.json'), 'utf8'))
    const w5 = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w5-additions.json'), 'utf8'))
    const w6 = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w6-additions.json'), 'utf8'))
    const routes = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/design/react-full-migration/semantic-w6-routes.json'), 'utf8'))
    for (const key of ['objects', 'actions'] as const) {
      for (const name of Object.keys(additions[key])) expect(design[key]).not.toHaveProperty(name)
      for (const name of Object.keys(w3[key])) {
        expect(design[key]).not.toHaveProperty(name)
        expect(additions[key]).not.toHaveProperty(name)
      }
      for (const name of Object.keys(w4[key])) {
        expect(design[key]).not.toHaveProperty(name)
        expect(additions[key]).not.toHaveProperty(name)
        expect(w3[key]).not.toHaveProperty(name)
      }
      for (const name of Object.keys(w5[key])) {
        expect(design[key]).not.toHaveProperty(name)
        expect(additions[key]).not.toHaveProperty(name)
        expect(w3[key]).not.toHaveProperty(name)
        expect(w4[key]).not.toHaveProperty(name)
      }
      expect(uiSemantics[key]).toEqual({ ...design[key], ...additions[key], ...w3[key], ...w4[key], ...w5[key], ...w6[key] })
    }
    for (const key of ['guards', 'components'] as const) expect(uiSemantics[key]).toEqual(design[key])
    expect(uiSemantics.routes).toEqual(design.routes.map((route: {path:string}) => ({...route,...routes[route.path]})))
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
