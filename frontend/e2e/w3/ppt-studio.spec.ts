import { expect, test } from '@playwright/test'
import { SKIN_STORAGE_KEY } from '../../src/themes/registry'
import { semanticName } from './semantics'
import { observeW2Resources, assertW2Disposed } from '../w2/resources'
import { pptDocument, pptProductFixture } from './pptFixture'
import { immediateW3Exit, record, stableShot } from './evidence'

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  test(`${skin} actual PPT studio direct/reload/back three-dimensional page and strict exit`, async ({ page }) => {
    const fixture = await pptProductFixture(page); await page.route('**/api/interactions**', route => route.fulfill({ json: [] })); await observeW2Resources(page)
    await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await page.goto(`/ppt/${pptDocument.id}`)
    await expect(page.locator('[data-react-page="nav.ppt"]')).toHaveCount(1); await expect(page.getByRole('heading', { name: pptDocument.title, exact: true })).toBeVisible(); await page.reload(); await expect(page.getByRole('heading', { name: pptDocument.title, exact: true })).toBeVisible()
    await page.locator('[data-semantic="ppt.editObject"]').click(); await expect(page.locator('[data-canvas-runtime="react"][data-canvas-kind="ppt"]')).toBeVisible(); await expect(page.locator('[data-canvas-kind="ppt-navigator"]')).toBeVisible()
    await page.locator('[data-semantic="ui.cancelEditing"]').click()
    await expect(page.locator('.ppt-preview-image')).toBeVisible()
    await expect.poll(() => page.evaluate(() => { const image = document.querySelector<HTMLImageElement>('.ppt-preview-image')!; return { width: image.naturalWidth, height: image.naturalHeight } })).toEqual({ width: 960, height: 540 })
    await stableShot(page, `${skin}-ppt-default.png`)
    await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot()); expect(before.rootConnected).toBe(true); expect(before.listeners.some(row => row.type === 'beforeunload')).toBe(true); expect(before.applicationMediaListeners.some(row => row.type === 'change')).toBe(true); expect(before.sentinel.active).toBe(true)
    const immediate = await immediateW3Exit(page, '[data-react-page] a[href="/ppt"]'); await record(`${skin}-ppt-default-exit.json`, { before, immediate }); assertW2Disposed(before, immediate)
    await expect(page).toHaveURL(/\/ppt$/); await page.goBack(); await expect(page.getByRole('heading', { name: pptDocument.title, exact: true })).toBeVisible(); await page.goForward(); await expect(page).toHaveURL(/\/ppt$/)
    expect(fixture.requests.filter(request => request.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([])
  })
  for (const kind of ['drag', 'resize'] as const) test(`${skin} PPT actual ${kind} capture immediately clears on SPA exit for three cycles`, async ({ page }) => {
    const fixture = await pptProductFixture(page); await page.route('**/api/interactions**', route => route.fulfill({ json: [] })); await observeW2Resources(page); await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await page.goto(`/ppt/${pptDocument.id}`)
    const records = []
    for (let cycle = 0; cycle < 3; cycle++) {
      if (cycle) await page.goBack()
      await expect(page.getByRole('heading', { name: pptDocument.title, exact: true })).toBeVisible(); await page.locator('[data-semantic="ppt.editObject"]').click(); await page.locator('.ppt-canvas-object').first().focus(); await page.keyboard.press('Enter'); await expect(page.getByLabel('宽度', { exact: true })).toBeVisible()
      if (!cycle && kind === 'drag') await stableShot(page, `${skin}-ppt-selected.png`)
      await page.evaluate(() => { document.querySelector('.ppt-canvas')!.scrollIntoView({ block: 'center', behavior: 'instant' }) })
      const point = await page.evaluate(({ kind, deck }) => {
        const target = document.querySelector<HTMLElement>(kind === 'resize' ? '.ppt-resize-handle' : '.ppt-canvas-object')!, object = document.querySelector<HTMLElement>('.ppt-canvas-object')!, canvas = document.querySelector<HTMLElement>('.ppt-canvas')!
        const box = target.getBoundingClientRect(), objectBox = object.getBoundingClientRect(), surface = canvas.getBoundingClientRect(), element = deck.slides[0]!.elements[0]!, x = box.x + box.width / 2, y = box.y + box.height / 2, hit = document.elementFromPoint(x, y)
        if (!hit || !target.contains(hit)) throw new Error('Real PPT gesture target is obscured')
        return { x, y, beforeWidth: box.width, beforeX: box.x, pointScale: surface.width / deck.width, position: getComputedStyle(object).position,
          actual: { x: objectBox.x - surface.x - canvas.clientLeft, y: objectBox.y - surface.y - canvas.clientTop, width: objectBox.width, height: objectBox.height },
          expected: { x: element.x * canvas.clientWidth / deck.width, y: element.y * canvas.clientHeight / deck.height, width: element.width * canvas.clientWidth / deck.width, height: element.height * canvas.clientHeight / deck.height },
          clientWidth: canvas.clientWidth, clientHeight: canvas.clientHeight }
      }, { kind, deck: fixture.state.deck })
      expect(point.position).toBe('absolute')
      for (const coordinate of ['x', 'y', 'width', 'height'] as const) expect(point.actual[coordinate]).toBeCloseTo(point.expected[coordinate], 1)
      await page.evaluate(() => window.__w2Resources.begin()); await page.mouse.move(point.x, point.y); await page.mouse.down(); await page.mouse.move(point.x + 105, point.y + 20); await expect(page.locator('.ppt-canvas-object.dragging')).toHaveCount(1)
      const element = fixture.state.deck.slides[0]!.elements[0]!, dx = 105 / point.pointScale, dy = 20 / point.pointScale
      const expectedGeometry = kind === 'drag' ? { x: Math.round(element.x + dx), y: Math.round(element.y + dy), width: element.width, height: element.height } : { x: element.x, y: element.y, width: Math.round(element.width + dx), height: Math.round(element.height + dy) }
      const measure = () => page.evaluate(({ width, height }) => { const canvas = document.querySelector<HTMLElement>('.ppt-canvas')!, surface = canvas.getBoundingClientRect(), object = document.querySelector<HTMLElement>('.ppt-canvas-object.dragging')!.getBoundingClientRect(); return { x: Math.round((object.x - surface.x - canvas.clientLeft) * width / canvas.clientWidth), y: Math.round((object.y - surface.y - canvas.clientTop) * height / canvas.clientHeight), width: Math.round(object.width * width / canvas.clientWidth), height: Math.round(object.height * height / canvas.clientHeight), screen: { x: object.x - surface.x - canvas.clientLeft, y: object.y - surface.y - canvas.clientTop, width: object.width, height: object.height } } }, { width: fixture.state.deck.width, height: fixture.state.deck.height })
      await expect.poll(async () => { const { screen: _screen, ...geometry } = await measure(); return geometry }).toEqual(expectedGeometry)
      const moved = await measure()
      for (const coordinate of ['x', 'y', 'width', 'height'] as const) expect(moved.screen[coordinate]).toBeCloseTo(expectedGeometry[coordinate] * (coordinate === 'y' || coordinate === 'height' ? point.clientHeight / fixture.state.deck.height : point.clientWidth / fixture.state.deck.width), 1)
      const before = await page.evaluate(() => window.__w2Resources.snapshot()); expect(before.captures).toHaveLength(1); expect(before.rootConnected).toBe(true); expect(before.events.some(event => event.type === 'pointermove' && event.trusted)).toBe(true)
      const immediate = await immediateW3Exit(page, '[data-react-page] a[href="/ppt"]'); records.push({ cycle, kind, point, expectedGeometry, moved, before, immediate }); await record(`${skin}-ppt-${kind}-threecycles.json`, records); assertW2Disposed(before, immediate)
      await page.mouse.up(); await expect(page).toHaveURL(/\/ppt$/)
    }
    expect(fixture.requests.filter(request => request.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([])
  })
}

test('PPT real unknown discussion/body/theme/leave and identical explicit retry', async ({ page }) => {
  const writes: { body: object; method: string; path: string }[] = []
  const fixture = await pptProductFixture(page, { phase: 'BRIEFING', write: async (route, state) => {
    expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); expect(route.request().url()).toContain('/messages'); const body = route.request().postDataJSON(); writes.push({ body, method: route.request().method(), path: new URL(route.request().url()).pathname }); if (writes.length === 1) return route.abort('failed')
    const message = { ...body, id: 'message', documentId: pptDocument.id, answer: '已收到模拟讨论', state: 'COMPLETED', detail: '', version: 1, questions: [], createdAt: pptDocument.createdAt, updatedAt: pptDocument.updatedAt }; state.messages.push(message); return route.fulfill({ json: message })
  } }); await page.route('**/api/interactions**', route => route.fulfill({ json: [] })); await page.goto(`/ppt/${pptDocument.id}`); await page.getByLabel('向 PPT 助手发送要求').fill('明确的原制作要求'); await page.locator('[data-semantic="ppt.send"]').click(); await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible()
  const original = await page.evaluate(() => { const root = document.querySelector('[data-ppt-document]')!; const token = crypto.randomUUID(); root.setAttribute('data-test-identity', token); return { token, metadata: sessionStorage.getItem('loopper.ppt.pending.w3-ppt') } })
  for (const skin of ['tech-blue', 'github-white', 'spdb']) { await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin); await expect(page.locator('html')).toHaveAttribute('data-skin', skin); await expect(page.locator('[data-ppt-document]')).toHaveAttribute('data-test-identity', original.token); await expect(page.getByLabel('向 PPT 助手发送要求')).toHaveValue('明确的原制作要求'); expect(await page.evaluate(() => sessionStorage.getItem('loopper.ppt.pending.w3-ppt'))).toBe(original.metadata); expect(writes).toHaveLength(1) }
  await page.locator('[data-react-page] a[href="/ppt"]').click(); await expect(page).toHaveURL(new RegExp(`/ppt/${pptDocument.id}$`)); await page.locator('[data-semantic="receipt.retryOriginal"]').click(); await expect(page.locator('[data-operation-phase]')).toHaveCount(0); expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); await expect(page.getByLabel('向 PPT 助手发送要求')).toHaveValue(''); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
})
