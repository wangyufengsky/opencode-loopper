import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
const root = resolve(process.cwd(), 'src')
function files(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? files(join(directory, entry.name)) : /\.tsx?$/.test(entry.name) && !/\.spec\.|w0-contract|test-support|testFixtures/.test(entry.name) ? [join(directory, entry.name)] : [])
}
function resolveImport(from: string, target: string) {
  const path = target.startsWith('@/') ? join(root, target.slice(2)) : target.startsWith('.') ? resolve(dirname(from), target) : undefined
  return path && [path, path + '.ts', path + '.tsx', join(path, 'index.ts'), join(path, 'index.tsx')].find(candidate => existsSync(candidate) && /\.tsx?$/.test(candidate))
}
describe('W5 actual production ownership', () => {
  it('all creative React runtime dependencies are free of executable Vue/Pinia business delegates', () => {
    const pending = files(join(root, 'pages/w5')), seen = new Set<string>()
    expect(pending.length).toBeGreaterThan(4)
    while (pending.length) {
      const file = pending.pop()!
      if (seen.has(file)) continue
      seen.add(file); const source = readFileSync(file, 'utf8')
      const imports = [...source.matchAll(/(?:from\s*|import\s*\()["']([^"']+)["']/g)].map(match => match[1]!)
      for (const target of imports) {
        expect(target, file.slice(root.length)).not.toMatch(/\.vue$|^(?:vue|vue-router|pinia|@iconify\/vue|element-plus)(?:\/|$)/)
        const resolved = resolveImport(file, target)
        if (resolved) pending.push(resolved)
      }
    }
    expect(seen.size).toBeGreaterThan(20)
  })
  it('creative pages do not acquire history ownership, create per-page icon vocabularies or write through legacy task ports', () => {
    for (const file of files(join(root, 'pages/w5'))) {
      const source = readFileSync(file, 'utf8')
      expect(source, file.slice(root.length)).not.toMatch(/createBrowserRouter|history\.(?:pushState|replaceState)|legacy\.task\.(?:start|restart|activate|replace|add|remove)|@iconify-json|lucide:|<svg\b/)
    }
  })
  it('Designer keeps the original history-only guard and every W5 route uses the same public bridge', () => {
    const source = readFileSync(join(root, 'router/index.tsx'), 'utf8')
    for (const path of ['/requirements/new', '/requirements/:id', '/workflows/new', '/workflows/:id', '/designer']) expect(source).toContain(`'${path}'`)
    expect(source).toContain('designerEntry(routeFromLocation'); expect(source).toContain('<RouteScreen application={application}')
    const designer = readFileSync(join(root,'pages/w5/designer/DesignerPage.tsx'),'utf8'); expect(designer).toContain('historyOnly')
    expect(source).not.toContain('.vue')
  })
})
