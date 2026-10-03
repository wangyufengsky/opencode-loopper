import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
export { immediateW3Exit as immediateExit } from '../w3/evidence'
export const evidence = process.env.CANVAS_W4_EVIDENCE_DIR ?? 'test-results/w4-production'
export async function record(name: string, value: unknown) { await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), JSON.stringify(value, null, 2)) }
export async function stableShot(page: Page, name: string) {
  await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].filter(image => !image.loading || image.loading !== 'lazy').map(image => image.decode().catch(() => undefined))); window.scrollTo({ top: 0, behavior: 'instant' }) })
  let previous: Buffer | undefined, stable: Buffer | undefined
  const hashes: string[] = []
  for (let attempt = 0; attempt < 12; attempt++) { await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))); const current = await page.screenshot({ animations: 'disabled' }); hashes.push(createHash('sha256').update(current).digest('hex')); if (previous?.equals(current)) { stable = current; break }; previous = current }
  expect(stable, 'Two exact consecutive screenshot buffers must be stable').toBeTruthy()
  await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, name), stable!); await record(name.replace('.png', '.stability.json'), { hashes, exact: true, sha256: createHash('sha256').update(stable!).digest('hex') })
}
