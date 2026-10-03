import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { immediateW3Exit } from '../w3/evidence'
/** Canvas routes keep application navigation collapsed. Use its real toggle,
 * then its actual SPA link; never bypass the guard or synthesize a gesture end. */
export async function immediateExit(page: Page, selector = '.app-sidebar a[href="/template-tasks"]', expectedGesture?: 'pan' | 'drag' | 'connect') {
  const beforeRoute = await page.evaluate(async ({ selector, expectedGesture }) => {
    if (!document.querySelector(selector)) {
      const toggle = document.querySelector<HTMLButtonElement>('.canvas-navigation-toggle')
      if (!toggle) throw new Error('真实生产导航入口缺失')
      toggle.click(); await Promise.resolve(); await Promise.resolve()
    }
    if (!document.querySelector(selector)) throw new Error('真实导航未展开')
    const marker = document.querySelector('[data-canvas-kind="workflow"]')?.getAttribute('data-pointer-gesture') ?? null
    const captures = window.__w2Resources.snapshot().captures
    if (expectedGesture && (marker !== expectedGesture || captures.length !== 1)) throw new Error('路由点击前，原活动手势或实例 capture 已消失')
    return { expectedGesture: expectedGesture ?? null, marker, captures }
  }, { selector, expectedGesture })
  const immediate = await immediateW3Exit(page, selector)
  return { ...immediate, gestureBeforeRoute: beforeRoute }
}
export const evidence = process.env.CANVAS_W5_EVIDENCE_DIR ?? 'test-results/w5-production'
export async function record(name: string, value: unknown) { await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), JSON.stringify(value, null, 2)) }
export async function stableShot(page: Page, name: string) {
  await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].filter(image => !image.loading || image.loading !== 'lazy').map(image => image.decode().catch(() => undefined))); window.scrollTo({ top: 0, behavior: 'instant' }) })
  let previous: Buffer | undefined, stable: Buffer | undefined
  const hashes: string[] = []
  for (let attempt = 0; attempt < 12; attempt++) { await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))); const current = await page.screenshot({ animations: 'disabled' }); hashes.push(createHash('sha256').update(current).digest('hex')); if (previous?.equals(current)) { stable = current; break }; previous = current }
  expect(stable, 'Two exact consecutive screenshot buffers must be stable').toBeTruthy()
  await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), stable!); await record(name.replace('.png', '.stability.json'), { hashes, exact: true, sha256: createHash('sha256').update(stable!).digest('hex') })
}
