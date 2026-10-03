import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { W2ResourceSnapshot } from '../w2/resources'
export const evidence = process.env.CANVAS_W3_EVIDENCE_DIR ?? 'test-results/w3-production'
export async function record(name: string, value: unknown) { await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), JSON.stringify(value, null, 2)) }
export async function stableShot(page: Page, name: string) {
  await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].filter(image => !image.loading || image.loading !== 'lazy').map(image => image.decode().catch(() => undefined))); window.scrollTo({ top: 0, behavior: 'instant' }) })
  let previous: Buffer | undefined, stable: Buffer | undefined
  const hashes: string[] = []
  for (let attempt = 0; attempt < 12; attempt++) { await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))); const current = await page.screenshot({ animations: 'disabled' }); hashes.push(createHash('sha256').update(current).digest('hex')); if (previous?.equals(current)) { stable = current; break }; previous = current }
  expect(stable, 'Two exact consecutive screenshot buffers must be stable').toBeTruthy()
  await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), stable!); await record(name.replace('.png', '.stability.json'), { hashes, exact: true, sha256: createHash('sha256').update(stable!).digest('hex') })
}
/** A DOM click starts the actual router intent; observe the first removal microtask, with no cleanup input. */
export function immediateW3Exit(page: Page, linkSelector = '.app-sidebar a[href="/inbox"]'): Promise<W2ResourceSnapshot> {
  return page.evaluate(linkSelector => new Promise<W2ResourceSnapshot>(resolve => {
    const root = document.querySelector('[data-react-page]')!, link = document.querySelector<HTMLAnchorElement>(linkSelector)
    if (!root || !link) throw new Error('Production root or actual Inbox navigation is missing')
    const observer = new MutationObserver(() => { if (!root.isConnected) { observer.disconnect(); resolve(window.__w2Resources.snapshot()) } }); observer.observe(document.body, { childList: true, subtree: true }); link.click()
  }), linkSelector)
}
