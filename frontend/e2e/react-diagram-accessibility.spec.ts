import { expect, test, type Page } from '@playwright/test'
import { canvasReviewFixture } from './fixtures/canvasReview'
import { SKIN_STORAGE_KEY } from '../src/themes/registry'

const evidence = process.env.CANVAS_EVIDENCE_DIR ?? 'test-results/react-canvas'
type Streams = { opened: string[]; closed: string[] }
async function longSequenceFixture(page: Page, template: boolean) {
  const fixture = await canvasReviewFixture(page)
  const id = template ? 'long-template-diagram' : 'long-stage-diagram'
  const stages = Array.from({ length: 20 }, (_, index) => ({ id: `stage-${index + 1}`, ordinal: index + 1,
    objective: `核对阶段${index + 1}的完整目标与验收证据。`, status: index === 19 ? 'RUNNING' : 'SUCCEEDED', attemptCount: 1, attempts: [] }))
  const steps = Array.from({ length: 20 }, (_, index) => ({ key: `step-${index + 1}`, label: `核对步骤${index + 1}的证据`, state: index === 19 ? 'ACTIVE' : 'COMPLETE' }))
  const task = { id, projectId: 'project', projectName: '长序列验收 · 模拟数据', title: '只读流程可达性 · 模拟数据', goal: '核对键盘定位与文字选择',
    status: 'RUNNING', version: 4, attemptCount: 20, maxAttempts: 30, hasDesignHistory: false, archived: false,
    executionMode: template ? 'TEMPLATE_REPORT' : 'WORKTREE', branch: 'main', worktreePath: '/tmp/mock-project',
    loopRetryAvailable: false, cancellationAvailable: true, createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
    stages, attempts: [], errors: [], judges: [], artifacts: [], workPackages: [],
    ...(template ? { templateProgress: { reviewBatches: 20, contributorBatches: 0, completedReviews: 19, completedContributors: 0,
      activeBatches: 1, failedBatches: 0, repairRound: 0, reportCount: 0, documentPath: '/tmp/mock-report', steps } } : {}),
  }
  await page.addInitScript(() => {
    const counts: Streams = { opened: [], closed: [] }
    ;(window as unknown as { __diagramStreams: Streams }).__diagramStreams = counts
    // Hold the original owner subscription open without a backend or browser reconnect timer.
    class FixtureStream extends EventTarget {
      onopen: ((event: Event) => void) | null = null
      onmessage: ((event: MessageEvent) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      readyState = 1
      constructor(readonly url: string) {
        super(); counts.opened.push(url); queueMicrotask(() => this.onopen?.(new Event('open')))
      }
      close() { this.readyState = 2; counts.closed.push(this.url) }
    }
    window.EventSource = FixtureStream as unknown as typeof EventSource
  })
  await page.route(/^http:\/\/127\.0\.0\.1:\d+\/api\/(?:tasks|template-tasks)\//, async route => {
    if (route.request().method() !== 'GET') return route.fallback()
    const path = new URL(route.request().url()).pathname
    if (path === `/api/tasks/${id}/overview` || path === `/api/tasks/${id}`) return route.fulfill({ json: task })
    if (path === '/api/tasks/summaries') return route.fulfill({ json: { items: [], facets: {}, nextCursor: null } })
    if (path.endsWith('/audit')) return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: [] } })
    if (path.endsWith('/template-progress')) return route.fulfill({ json: task.templateProgress ?? null })
    if (path.endsWith('/failed-batches')) return route.fulfill({ json: { items: [], facets: {}, nextCursor: null } })
    if (path.endsWith('/sessions/current')) return route.fulfill({ status: 204 })
    return route.fallback()
  })
  return { ...fixture, id }
}

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  for (const template of [false, true]) {
    test(`${skin} 320px ${template ? '模板步骤' : '任务阶段'}长序列Tab定位与原生文字选择不重建订阅（模拟数据）`, async ({ page }) => {
      const fixture = await longSequenceFixture(page, template)
      await page.addInitScript(({ key, value }) => localStorage.setItem(key, value), { key: SKIN_STORAGE_KEY, value: skin })
      await page.setViewportSize({ width: 320, height: 844 }); await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.goto(`/tasks/${fixture.id}`)
      const kind = template ? 'template-progress' : 'stages'
      const canvas = page.locator(`.readonly-diagram[data-canvas-kind="${kind}"]`)
      const nodes = canvas.locator('.react-flow__node'), last = nodes.last()
      await expect(nodes).toHaveCount(20); await expect(canvas.locator('.react-flow__edge')).toHaveCount(19)
      await expect.poll(() => page.evaluate(id => (window as unknown as { __diagramStreams: Streams }).__diagramStreams.opened.filter(url => url.endsWith(`/tasks/${id}/events`)).length, fixture.id)).toBe(1)
      const originalStreams = await page.evaluate(() => (window as unknown as { __diagramStreams: Streams }).__diagramStreams)
      await canvas.scrollIntoViewIfNeeded(); await nodes.first().focus()
      for (let index = 1; index < 20; index++) { await page.keyboard.press('Tab'); await expect(nodes.nth(index)).toBeFocused() }
      await expect.poll(async () => {
        const box = (await last.boundingBox())!, bounds = (await canvas.boundingBox())!
        const middle = box.x + box.width / 2
        return middle >= bounds.x && middle <= bounds.x + bounds.width
      }).toBe(true)
      await expect(last).toHaveCSS('outline-style', 'solid')
      const position = await last.evaluate(element => (element as HTMLElement).style.transform)
      for (const key of ['ArrowRight', 'Enter', 'Space', 'Delete']) await page.keyboard.press(key)
      await expect(nodes).toHaveCount(20); await expect(canvas.locator('.react-flow__node.selected')).toHaveCount(0)
      expect(await last.evaluate(element => (element as HTMLElement).style.transform)).toBe(position)
      for (const next of ['spdb', 'tech-blue', 'github-white']) {
        await page.evaluate(({ key, value }) => {
          localStorage.setItem(key, value)
          dispatchEvent(new StorageEvent('storage', { key, newValue: value, storageArea: localStorage }))
        }, { key: SKIN_STORAGE_KEY, value: next })
        await expect(page.locator('html')).toHaveAttribute('data-skin', next)
        await expect(last).toBeFocused()
      }
      // The complete long sequence cannot fit at minZoom. Keyboard focus must reveal its last node.
      await canvas.getByRole('button', { name: '适应流程图', exact: true }).click()
      await nodes.nth(18).focus(); await page.keyboard.press('Tab'); await expect(last).toBeFocused()
      const text = last.locator(template ? 'strong' : '.phase-objective p')
      const range = await text.evaluate(element => {
        const node = element.firstChild!, range = document.createRange()
        range.setStart(node, 0); range.setEnd(node, 4)
        const box = range.getBoundingClientRect()
        return { x: box.x, y: box.y, width: box.width, height: box.height, expected: range.toString() }
      })
      const viewport = await canvas.locator('.react-flow__viewport').getAttribute('style')
      await page.mouse.move(range.x + 0.1, range.y + range.height / 2); await page.mouse.down()
      await page.mouse.move(range.x + range.width - 0.1, range.y + range.height / 2, { steps: 8 }); await page.mouse.up()
      await expect.poll(() => page.evaluate(() => getSelection()?.toString())).toBe(range.expected)
      expect(await canvas.locator('.react-flow__viewport').getAttribute('style')).toBe(viewport)
      expect(await page.locator('body').evaluate(element => element.scrollWidth <= innerWidth + 1)).toBe(true)
      const streams = await page.evaluate(() => (window as unknown as { __diagramStreams: Streams }).__diagramStreams)
      expect(streams).toEqual(originalStreams)
      expect(streams.opened.filter(url => url.endsWith(`/tasks/${fixture.id}/events`))).toHaveLength(1)
      await page.evaluate(({ key, value }) => {
        localStorage.setItem(key, value)
        dispatchEvent(new StorageEvent('storage', { key, newValue: value, storageArea: localStorage }))
      }, { key: SKIN_STORAGE_KEY, value: skin })
      await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
      await nodes.nth(18).focus(); await page.keyboard.press('Tab'); await expect(last).toBeFocused()
      expect(fixture.errors).toEqual([])
      await page.screenshot({ path: `${evidence}/${skin}-${kind}-long-keyboard.png`, fullPage: true })
      const oldRoot = await canvas.elementHandle()
      await page.getByRole('button', { name: '全部任务', exact: true }).click()
      await expect(page).toHaveURL('/tasks'); await expect(page.locator('.readonly-diagram')).toHaveCount(0)
      expect(await oldRoot!.evaluate(element => element.isConnected)).toBe(false); await oldRoot!.dispose()
      const disposed = await page.evaluate(() => (window as unknown as { __diagramStreams: Streams }).__diagramStreams)
      expect(disposed.opened).toEqual(originalStreams.opened)
      expect(disposed.closed).toEqual([...originalStreams.closed, `/api/tasks/${fixture.id}/events`])
      expect(disposed.opened.filter(url => url.endsWith('/story-accounting/events'))).toHaveLength(1)
      expect(disposed.closed.filter(url => url.endsWith('/story-accounting/events'))).toEqual([])
      expect(fixture.mutations).toEqual([]); expect(fixture.errors).toEqual([])
    })
  }
}
