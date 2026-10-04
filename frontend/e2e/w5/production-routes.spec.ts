import { expect, test, type Page, type Route, type Locator } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { SKIN_STORAGE_KEY } from '../../src/themes/registry'
import { semanticName } from '../w3/semantics'
import { observeW2Resources, assertW2Disposed } from '../w2/resources'
import { w5ProductFixture, flow, plan, session, project } from './productFixture'
import { evidence, record, stableShot, immediateExit } from './evidence'
const entries = [
  { kind: 'workflow-new', path: '/workflows/new' },
  { kind: 'workflow-edit', path: '/workflows/w5-flow' },
  { kind: 'requirement-new', path: '/requirements/new' },
  { kind: 'requirement-plan', path: '/requirements/w5-req' },
  { kind: 'designer', path: '/designer?sessionId=w5-session' },
] as const
async function prepared(page: Page, skin = 'spdb') {
  await observeW2Resources(page); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin })
}
async function loaded(page: Page, entry: typeof entries[number]) {
  await expect(page.locator('[data-react-page]')).toHaveCount(1)
  await expect(page.locator('[data-react-page] h1')).toBeVisible()
  if (entry.kind === 'workflow-edit') await expect(page.getByRole('heading', { name: flow.title, exact: true })).toBeVisible()
  if (entry.kind === 'requirement-plan') await expect(page.getByRole('heading', { name: plan.title, exact: true })).toBeVisible()
  if (entry.kind.startsWith('workflow') || entry.kind === 'requirement-plan') await expect(page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')).toBeVisible()
  if (entry.kind === 'requirement-new') await expect(page.locator('#workflow-requirement-title')).toBeVisible()
  if (entry.kind === 'designer') { await expect(page.locator('#designer-message')).toBeVisible(); await expect(page.getByText('请先确认报表范围，再进入设计。', { exact: false })).toBeVisible() }
  if (entry.kind === 'workflow-edit' || entry.kind === 'requirement-plan') {
    const visuals = await page.locator('[data-canvas-kind="workflow"]').evaluate(root => {
      const node = root.querySelector<HTMLElement>('.workflow-node')!, wires = [...root.querySelectorAll<SVGPathElement>('.workflow-wire')], controls = root.querySelector<HTMLElement>('.workflow-canvas-controls')!
      const style = getComputedStyle(node), canvas = root.getBoundingClientRect(), tools = controls.getBoundingClientRect()
      return { nodeWidth: parseFloat(style.width), nodeHeight: parseFloat(style.height), border: style.borderTopWidth, allWiresUnfilled: wires.every(wire => getComputedStyle(wire).fill === 'none'), toolsInside: tools.top >= canvas.top && tools.bottom <= canvas.bottom, toolsInDesktop: tools.top < innerHeight }
    })
    expect(visuals).toEqual({ nodeWidth: 224, nodeHeight: 118, border: '1px', allWiresUnfilled: true, toolsInside: true, toolsInDesktop: true })
  }
}
async function selected(page: Page, entry: typeof entries[number]) {
  if (entry.kind === 'workflow-edit' || entry.kind === 'requirement-plan') {
    await page.locator('.react-flow__node[data-id="review"]').click()
    await expect(page.getByRole('complementary')).toBeVisible()
    const panel = (await page.getByRole('complementary').boundingBox())!, desktop = page.viewportSize()!
    expect(panel.y).toBeLessThan(desktop.height - 100); expect(panel.x + panel.width).toBeLessThanOrEqual(desktop.width)
    expect(panel.width).toBeGreaterThanOrEqual(300)
  }
}
async function disposeActualReactRoot(page: Page) {
  return page.evaluate(() => {
    const host = document.querySelector('[data-app-route-owner]') as import('../../src/migration/reactViewLifecycle').ReactViewHost
    if (!host?.reactViewLifecycle) throw new Error('实际生产 root 的公开生命周期接口缺失')
    const disposed = host.reactViewLifecycle.disposeIfSafe()
    return { disposed, immediate: window.__w2Resources.snapshot() }
  })
}
// Initialize Playwright's own main-world hit testing before an application root exists.
// This does not exempt unknown callbacks or change the strict resource ledger.
async function startWithRunnerReady(page: Page, path?: string) {
  let release!: () => void
  const hold = new Promise<void>(resolve => { release = resolve })
  const modules = async (route: Route) => { await hold; await route.continue() }
  await page.route('**/assets/*.js', modules)
  try {
    if (path) await page.goto(path, { waitUntil: 'commit' }); else await page.reload({ waitUntil: 'commit' }); await expect(page.locator('#app')).toHaveCount(1)
    await expect(page.locator('[data-react-page]')).toHaveCount(0)
    await page.locator('html').evaluate(element => element.tagName)
    await page.locator('html').click({ position: { x: 400, y: 100 } })
  } finally { release() }
  await page.unroute('**/assets/*.js', modules); await page.waitForLoadState('load')
}
async function blankPane(canvas: Locator) {
  return canvas.evaluate(element => {
    const pane = element.querySelector('.react-flow__pane')!, box = pane.getBoundingClientRect()
    for (let y = box.bottom - 80; y > box.top + 80; y -= 40) for (let x = box.left + 80; x < box.right - 190; x += 40) {
      if (document.elementFromPoint(x, y) === pane && document.elementFromPoint(x + 105, y + 20) === pane) return { x, y }
    }
    throw new Error('真实生产画布没有可见空白平移起止点')
  })
}
async function viewport(canvas: Locator) {
  return canvas.locator('.react-flow__viewport').evaluate(element => { const m = new DOMMatrix(getComputedStyle(element).transform); return { x: m.e, y: m.f, zoom: m.a } })
}
for (const skin of ['spdb', 'tech-blue', 'github-white']) for (const entry of entries) test(`${skin} production ${entry.kind} direct/reload/back/forward and 3 strict immediate route exits`, async ({ page }) => {
  const fixture = await w5ProductFixture(page); await prepared(page, skin); await startWithRunnerReady(page, entry.path); await loaded(page, entry); await startWithRunnerReady(page); await loaded(page, entry)
  if (entry.kind === 'workflow-edit' || entry.kind === 'requirement-plan') await expect(page.locator('.workflow-wire')).toHaveCount(1)
  await stableShot(page, `${skin}-${entry.kind}-default.png`)
  const rounds = []
  for (let cycle = 0; cycle < 3; cycle++) {
    if (cycle) { await page.goBack(); await loaded(page, entry) }
    await selected(page, entry)
    if (!cycle && (entry.kind === 'workflow-edit' || entry.kind === 'requirement-plan')) await stableShot(page, `${skin}-${entry.kind}-selected.png`)
    await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot()), streamsBefore = await page.evaluate(() => (window as any).__w2Streams)
    const immediate = await immediateExit(page, '.app-sidebar a[href="/template-tasks"]'), streamsAfter = await page.evaluate(() => (window as any).__w2Streams)
    rounds.push({ cycle, before, immediate, streamsBefore, streamsAfter }); await record(`${skin}-${entry.kind}-threecycles.json`, rounds); assertW2Disposed(before, immediate)
    if (entry.kind === 'designer') { const path = `/api/designer-sessions/${session.id}/events`; expect(streamsBefore.opened.filter((x: string) => x === path).length - streamsBefore.closed.filter((x: string) => x === path).length).toBe(1); expect(streamsAfter.opened.filter((x: string) => x === path)).toHaveLength(streamsAfter.closed.filter((x: string) => x === path).length) }
    expect(streamsAfter.closed.filter((x: string) => x.includes('story-accounting'))).toEqual(streamsBefore.closed.filter((x: string) => x.includes('story-accounting')))
    await expect(page).toHaveURL(/\/template-tasks$/)
  }
  await page.goBack(); await loaded(page, entry); await page.goForward(); await expect(page).toHaveURL(/\/template-tasks$/)
  expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.base.requests.filter(row => row.method !== 'GET')).toEqual([])
  expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
for (const entry of entries) test(`actual ${entry.kind} root shutdown immediately releases every owned resource`, async ({ page }) => {
  const fixture = await w5ProductFixture(page); await prepared(page); await startWithRunnerReady(page, entry.path); await loaded(page, entry); await selected(page, entry)
  await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot())
  const result = await disposeActualReactRoot(page); expect(result.disposed).toBe(true); await record(`${entry.kind}-root-shutdown.json`, { before, ...result }); assertW2Disposed(before, result.immediate)
  expect(fixture.base.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
})
for (const exit of ['route', 'root'] as const) test(`native Designer SSE exact reconnect cursor + REST authority + ${exit} strict cleanup`, async ({ page }) => {
  const id = `w5-native-${exit}`, fixture = await w5ProductFixture(page, { native: true, sessionId: id }); await prepared(page); await startWithRunnerReady(page, `/designer?sessionId=${id}`); await loaded(page, entries[4])
  const path = `/api/designer-sessions/${id}/events`
  const rows = async () => JSON.parse(await readFile(join(evidence, 'native-sse.json'), 'utf8')) as { path: string; cursor: string | null; closed: number | null }[]
  await expect.poll(async () => (await rows()).filter(row => row.path === path).map(row => row.cursor)).toContain('17')
  await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot())
  const immediate = exit === 'route' ? await immediateExit(page, '.app-sidebar a[href="/template-tasks"]') : (await disposeActualReactRoot(page)).immediate
  assertW2Disposed(before, immediate); await expect.poll(async () => (await rows()).filter(row => row.path === path).every(row => row.closed !== null)).toBe(true)
  const reads = fixture.requests.filter(row => row.path === `/api/designer-sessions/${id}`).length
  await page.waitForTimeout(450); expect(fixture.requests.filter(row => row.path === `/api/designer-sessions/${id}`)).toHaveLength(reads)
  await record(`native-designer-${exit}.json`, { before, immediate, rows: await rows() }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
for (const entry of [entries[1], entries[3]]) for (const gesture of ['pan', 'drag', 'connect'] as const) for (const exit of ['route', 'root'] as const) test(`${entry.kind} actual active ${gesture} ${exit} releases resources before any subsequent input`, async ({ page }) => {
  const fixture = await w5ProductFixture(page); await prepared(page); await startWithRunnerReady(page, entry.path); await loaded(page, entry)
  const canvas = page.locator('[data-canvas-kind="workflow"]'), node = canvas.locator('[data-node-id="review"]')
  const origin = gesture === 'pan' ? await blankPane(canvas) : await (gesture === 'connect' ? canvas.locator('.react-flow__node[data-id="review"] .react-flow__handle.source') : node).evaluate(element => { const b = element.getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 } })
  await page.evaluate(() => window.__w2Resources.begin()); const initialViewport = await viewport(canvas)
  const nodeBefore = await node.boundingBox()
  await page.mouse.move(origin.x, origin.y); await page.mouse.down(); await page.mouse.move(origin.x + 105, origin.y + 20)
  await expect(canvas).toHaveAttribute('data-pointer-gesture', gesture)
  if (gesture === 'connect') {
    const end = await canvas.locator('.workflow-connection-preview path').evaluate(element => { const path = element as SVGPathElement, point = path.getPointAtLength(path.getTotalLength()), matrix = path.getScreenCTM()!; return { x: point.x * matrix.a + point.y * matrix.c + matrix.e, y: point.x * matrix.b + point.y * matrix.d + matrix.f } })
    expect(end.x).toBeCloseTo(origin.x + 105, 1); expect(end.y).toBeCloseTo(origin.y + 20, 1)
  }
  if (gesture === 'pan') { const after = await viewport(canvas); expect(after.x - initialViewport.x).toBeCloseTo(105, 2); expect(after.y - initialViewport.y).toBeCloseTo(20, 2) }
  if (gesture === 'drag') { const after = await node.boundingBox(); expect(after!.x - nodeBefore!.x).toBeCloseTo(105, 2); expect(after!.y - nodeBefore!.y).toBeCloseTo(20, 2) }
  const before = await page.evaluate(() => window.__w2Resources.snapshot())
  const result = exit === 'root' ? await disposeActualReactRoot(page) : { disposed: true, immediate: await immediateExit(page, '.app-sidebar a[href="/template-tasks"]', gesture) }
  expect(result.disposed).toBe(true); assertW2Disposed(before, result.immediate)
  await record(`${entry.kind}-active-${gesture}-${exit}.json`, { initialViewport, nodeBefore, before, ...result })
  await page.mouse.up(); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
for (const skin of ['spdb', 'tech-blue', 'github-white']) test(`${skin} actual new requirement UNKNOWN blocks departure, keeps four fields and explicitly retries identical POST`, async ({ page }) => {
  let posts = 0
  const fixture = await w5ProductFixture(page, { write: async route => {
    expect(new URL(route.request().url()).pathname).toBe('/api/workflows/requirements'); expect(route.request().method()).toBe('POST')
    if (++posts === 1) return route.abort('failed')
    return route.fulfill({ json: { id: plan.id, revision: plan.revision, version: plan.version, layoutVersion: plan.layoutVersion, state: plan.state } })
  } })
  await prepared(page, skin); await startWithRunnerReady(page, '/template-tasks'); await expect(page.locator('[data-react-page] h1')).toBeVisible(); await startWithRunnerReady(page, `/requirements/new?projectId=${project.id}`); await loaded(page, entries[2])
  const title = page.locator('#workflow-requirement-title'), objective = page.locator('#workflow-requirement-objective')
  await title.fill('原始四字段需求 · 模拟'); await objective.fill('保持原创建身份和输入。')
  await page.locator('[data-semantic="workflow.createRequirement"]').click()
  await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible(); await expect(title).toBeDisabled(); await expect(objective).toBeDisabled()
  await expect(page.locator('[data-semantic="workflow.chooseProject"]')).toBeDisabled(); await expect(page.locator('[data-semantic="workflow.chooseTemplate"]')).toBeDisabled()
  const documentIdentity = await page.evaluate(() => window.__w2Resources.snapshot().documentIdentity)
  let nativePrompts = 0
  page.on('dialog', async dialog => { expect(dialog.type()).toBe('beforeunload'); nativePrompts++; await dialog.dismiss() })
  const blockedNativeNavigation = (error: Error) => expect(error.name === 'TimeoutError' && /page\.(reload|goBack): Timeout 3000ms exceeded/.test(error.message) || /ERR_ABORTED/.test(error.message)).toBe(true)
  await page.reload({ waitUntil: 'commit', timeout: 3000 }).catch(blockedNativeNavigation); await expect.poll(() => nativePrompts).toBe(1)
  await page.goBack({ waitUntil: 'commit', timeout: 3000 }).catch(blockedNativeNavigation); await expect.poll(() => nativePrompts).toBe(2)
  expect(await page.evaluate(() => window.__w2Resources.snapshot().documentIdentity)).toBe(documentIdentity)
  await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible(); expect(posts).toBe(1)
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/requirements\/new\?projectId=/)
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin === 'spdb' ? 'github-white' : 'spdb')
  await expect(title).toHaveValue('原始四字段需求 · 模拟'); expect(posts).toBe(1); expect(await disposeActualReactRoot(page)).toMatchObject({ disposed: false })
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin)
  await stableShot(page, `${skin}-requirement-new-unknown.png`)
  await page.locator('[data-semantic="workflow.retryCreate"]').click(); await expect(page).toHaveURL(`/requirements/${plan.id}`); await loaded(page, entries[3])
  const writes = fixture.requests.filter(row => row.method === 'POST'); expect(writes).toHaveLength(2); expect(writes[1]!.body).toBe(writes[0]!.body)
  const body = JSON.parse(writes[0]!.body!); expect(body).toMatchObject({ projectId: project.id, title: '原始四字段需求 · 模拟', objective: '保持原创建身份和输入。' }); expect(body.requestKey).toBeTruthy()
  await record(`${skin}-create-identical-recovery.json`, { writes, posts, nativePrompts, sameDocumentAfterRefreshBack: true, originalBodyEqual: true }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
for (const entry of [entries[1], entries[3]]) for (const zoom of [.5, 1, 2]) test(`${entry.kind} actual owner preserves first-frame 105px/20px at zoom ${zoom}, undo/redo and keyboard cancellation`, async ({ page }) => {
  const fixture = await w5ProductFixture(page), value = structuredClone(entry.kind === 'workflow-edit' ? flow : plan); value.layout.zoom = zoom
  fixture.set(entry.kind === 'workflow-edit' ? '/api/workflows/templates/w5-flow' : '/api/workflows/requirements/w5-req', value)
  await prepared(page); await startWithRunnerReady(page, entry.path); await loaded(page, entry)
  const canvas = page.locator('[data-canvas-kind="workflow"]'), node = canvas.locator('[data-node-id="review"]'), before = (await node.boundingBox())!
  const origin = { x: before.x + before.width / 2, y: before.y + before.height / 2 }
  await page.mouse.move(origin.x, origin.y); await page.mouse.down(); await page.mouse.move(origin.x + 105, origin.y + 20)
  const moving = (await node.boundingBox())!; expect(moving.x - before.x).toBeCloseTo(105, 2); expect(moving.y - before.y).toBeCloseTo(20, 2)
  await page.mouse.up(); await expect(canvas).not.toHaveAttribute('data-pointer-gesture', /./); const committed = (await node.boundingBox())!; expect(committed.x).toBeCloseTo(moving.x, 2)
  await page.keyboard.press('Escape'); await expect(page.getByRole('complementary')).toHaveCount(0)
  if (entry.kind === 'workflow-edit') { await node.focus(); await page.keyboard.press('Control+z') }
  else { await page.locator('[data-semantic="workflow.undo"]').focus(); await page.keyboard.press('Enter') }
  await expect.poll(async () => (await node.boundingBox())!.x).toBeCloseTo(before.x, 2)
  if (entry.kind === 'workflow-edit') await page.keyboard.press('Control+Shift+z')
  else { await page.locator('[data-semantic="workflow.redo"]').focus(); await page.keyboard.press('Enter') }
  await expect.poll(async () => (await node.boundingBox())!.x).toBeCloseTo(committed.x, 2)
  await record(`${entry.kind}-first-frame-zoom-${zoom}.json`, { before, moving, committed, zoom, screenDisplacement: { x: 105, y: 20 } }); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
for (const skin of ['spdb', 'tech-blue', 'github-white']) test(`${skin} actual workflow connects through React Flow handles, retains state on theme, then undo/redo without implicit write`, async ({ page }) => {
  const fixture = await w5ProductFixture(page), original = structuredClone(flow); original.graph.edges = []; fixture.set('/api/workflows/templates/w5-flow', original)
  await prepared(page, skin); await startWithRunnerReady(page, entries[1].path); await loaded(page, entries[1]); const canvas = page.locator('[data-canvas-kind="workflow"]')
  const from = await canvas.locator('.react-flow__node[data-id="review"] .react-flow__handle.source').evaluate(element => { const b = element.getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 } }), to = await canvas.locator('.react-flow__node[data-id="delivery"] .react-flow__handle.target').evaluate(element => { const b = element.getBoundingClientRect(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 } })
  await page.mouse.move(from.x, from.y); await page.mouse.down(); await page.mouse.move(to.x, to.y, { steps: 4 }); await expect(canvas.locator('.workflow-connection-preview')).toBeVisible(); await page.mouse.up(); await expect(canvas.locator('.react-flow__edge')).toHaveCount(1); await expect(canvas).not.toHaveAttribute('data-pointer-gesture', /./)
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin === 'spdb' ? 'github-white' : 'spdb'); await expect(canvas.locator('.react-flow__edge')).toHaveCount(1)
  await canvas.focus(); await page.keyboard.press('Control+z'); await expect(canvas.locator('.react-flow__edge')).toHaveCount(0); await page.keyboard.press('Control+Shift+z'); await expect(canvas.locator('.react-flow__edge')).toHaveCount(1)
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin)
  await stableShot(page, `${skin}-workflow-normal-connected.png`); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
test('actual workflow graph accepted/layout unknown/final GET failure each retains its own original receipt without rewriting accepted stages', async ({ page }) => {
  let graphs = 0, layouts = 0, accepted = false, finalReadFailed = false
  const saved = { ...flow, title: '修改后的流程 · 模拟', revision: flow.revision + 1, version: flow.version + 1, layoutVersion: flow.layoutVersion + 1 }
  const fixture = await w5ProductFixture(page, { write: async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/workflows/templates/w5-flow') { graphs++; return route.fulfill({ json: { id: flow.id, revision: saved.revision, version: saved.version, layoutVersion: flow.layoutVersion, state: 'ACTIVE' } }) }
    expect(path).toBe('/api/workflows/templates/w5-flow/layout'); if (++layouts === 1) return route.abort('failed')
    accepted = true; fixture.set('/api/workflows/templates/w5-flow', saved); return route.fulfill({ json: { id: flow.id, revision: saved.revision, version: saved.version, layoutVersion: saved.layoutVersion, state: 'ACTIVE' } })
  } })
  await page.route('**/api/workflows/templates/w5-flow', async route => { if (route.request().method() === 'GET' && accepted && !finalReadFailed) { finalReadFailed = true; return route.fulfill({ status: 503, json: { message: '已接受写入后的模拟读取断开' } }) } return route.fallback() })
  await prepared(page); await startWithRunnerReady(page, entries[1].path); await loaded(page, entries[1]); await page.locator('[data-semantic="workflow.settings"]').click(); await page.getByLabel('流程名称', { exact: true }).fill(saved.title); await page.locator('[data-semantic="workflow.save"]').click()
  await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible(); expect(graphs).toBe(1); expect(layouts).toBe(1); await expect(page.getByLabel('流程名称', { exact: true })).toBeDisabled()
  await page.getByRole('button', { name: semanticName('ui.expand', semanticName('app.navigation')), exact: true }).click(); await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(entries[1].path); await page.getByRole('button', { name: semanticName('ui.collapse', semanticName('app.navigation')), exact: true }).click(); expect((await disposeActualReactRoot(page)).disposed).toBe(false)
  await page.locator('[data-operation-phase="UNKNOWN"] [data-semantic="receipt.retryOriginal"]').click()
  await expect(page.locator('[data-operation-phase="ACCEPTED_READBACK"]')).toBeVisible(); expect(graphs).toBe(1); expect(layouts).toBe(2); expect(finalReadFailed).toBe(true)
  await stableShot(page, 'spdb-workflow-partial-accepted.png')
  await page.locator('[data-operation-phase="ACCEPTED_READBACK"] [data-semantic="receipt.readOriginal"]').click(); await expect(page.getByText('流程已保存', { exact: true })).toBeVisible()
  expect(graphs).toBe(1); expect(layouts).toBe(2); const layoutWrites = fixture.requests.filter(row => row.path.endsWith('/layout') && row.method === 'PUT'); expect(layoutWrites[1]!.body).toBe(layoutWrites[0]!.body)
  await record('workflow-two-stage-original-identity.json', { graphs, layouts, finalReadFailed, layoutWrites }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
test('actual workflow accepted graph plus definitive layout rejection retains partial identity and only explicitly retries that same layout', async ({ page }) => {
  let graphs = 0, layouts = 0
  const saved = { ...flow, title: '分段保存核对 · 模拟', revision: flow.revision + 1, version: flow.version + 1, layoutVersion: flow.layoutVersion + 1 }
  const fixture = await w5ProductFixture(page, { write: async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/workflows/templates/w5-flow') { graphs++; return route.fulfill({ json: { id: flow.id, revision: saved.revision, version: saved.version, layoutVersion: flow.layoutVersion, state: 'ACTIVE' } }) }
    expect(path).toBe('/api/workflows/templates/w5-flow/layout'); layouts++
    if (layouts <= 2) return route.fulfill({ status: 409, json: { message: '模拟布局版本冲突，原图定义已经接受' } })
    fixture.set('/api/workflows/templates/w5-flow', saved)
    return route.fulfill({ json: { id: flow.id, revision: saved.revision, version: saved.version, layoutVersion: saved.layoutVersion, state: 'ACTIVE' } })
  } })
  await prepared(page); await startWithRunnerReady(page, entries[1].path); await loaded(page, entries[1]); await page.locator('[data-semantic="workflow.settings"]').click()
  await page.getByLabel('流程名称', { exact: true }).fill(saved.title); await page.locator('[data-semantic="workflow.save"]').click()
  await expect(page.locator('[data-operation-phase="PARTIAL_REJECTION"]')).toBeVisible(); await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toHaveCount(0); await expect(page.getByText('图定义已接受。', { exact: false })).toBeVisible(); expect(graphs).toBe(1); expect(layouts).toBe(1)
  await expect(page.getByLabel('流程名称', { exact: true })).toBeDisabled(); expect((await disposeActualReactRoot(page)).disposed).toBe(false)
  await page.getByRole('button', { name: semanticName('ui.expand', semanticName('app.navigation')), exact: true }).click(); await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(entries[1].path); await page.getByRole('button', { name: semanticName('ui.collapse', semanticName('app.navigation')), exact: true }).click()
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption('tech-blue'); expect(graphs).toBe(1); expect(layouts).toBe(1)
  await page.locator('[data-semantic="receipt.retryOriginal"]').first().click(); await expect(page.getByText('图定义已接受。', { exact: false })).toBeVisible(); expect(graphs).toBe(1); expect(layouts).toBe(2)
  expect((await disposeActualReactRoot(page)).disposed).toBe(false); await stableShot(page, 'tech-blue-workflow-partial-rejected.png')
  await page.locator('[data-semantic="receipt.retryOriginal"]').first().click(); await expect(page.getByText('流程已保存', { exact: true })).toBeVisible()
  expect(graphs).toBe(1); expect(layouts).toBe(3)
  const originals = fixture.requests.filter(row => row.path.endsWith('/layout') && row.method === 'PUT').map(row => row.body)
  expect(originals).toHaveLength(3); expect(originals[1]).toBe(originals[0]); expect(originals[2]).toBe(originals[0])
  await record('workflow-definite-partial-identity.json', { graphs, layouts, originals, noImplicitRetry: true }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
test('actual Designer UNKNOWN multipart Send retries original metadata/File bytes while retaining later message and extra attachment', async ({ page }) => {
  const multipart: { metadata: string; files: { name: string; bytes: string }[] }[] = []
  const fixture = await w5ProductFixture(page, { write: async route => {
    expect(new URL(route.request().url()).pathname).toBe('/api/designer-sessions/w5-session/context-turns')
    const request = route.request(), boundary = /boundary=(.+)$/.exec(request.headers()['content-type']!)![1]!, raw = request.postDataBuffer()!.toString('latin1')
    const parts = raw.split(`--${boundary}`).filter(part => part.includes('Content-Disposition')), parsed = { metadata: '', files: [] as { name: string; bytes: string }[] }
    for (const part of parts) { const at = part.indexOf('\r\n\r\n'), headers = part.slice(0, at), body = part.slice(at + 4, -2), filename = /filename="([^"]+)"/.exec(headers); if (/name="metadata"/.test(headers)) parsed.metadata = Buffer.from(body, 'latin1').toString('utf8'); else if (filename) parsed.files.push({ name: filename[1]!, bytes: Buffer.from(body, 'latin1').toString('hex') }); else parsed.metadata = Buffer.from(body, 'latin1').toString('utf8') }
    multipart.push(parsed); if (multipart.length === 1) return route.abort('failed')
    return route.fulfill({ json: { sessionId: session.id, state: session.state, persistedMessages: [], notice: '' } })
  } })
  await prepared(page, 'github-white'); await startWithRunnerReady(page, entries[4].path); await loaded(page, entries[4]); const composer = page.getByRole('textbox', { name: '设计消息', exact: true })
  await composer.fill('原始附件消息'); await page.getByLabel('添加资料').setInputFiles({ name: 'original.txt', mimeType: 'text/plain', buffer: Buffer.from('original\u0000bytes\n中文') }); await page.locator('[data-semantic="designer.send"]').click()
  await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toBeVisible(); await composer.fill('后来编辑未发送'); await page.getByLabel('添加资料').setInputFiles({ name: 'later.txt', mimeType: 'text/plain', buffer: Buffer.from('later bytes') })
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(entries[4].path); expect((await disposeActualReactRoot(page)).disposed).toBe(false)
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption('tech-blue'); await expect(composer).toHaveValue('后来编辑未发送'); expect(multipart).toHaveLength(1)
  await stableShot(page, 'tech-blue-designer-unknown-file.png'); await page.locator('[data-semantic="designer.send"]').click(); await expect(page.locator('[data-operation-phase="UNKNOWN"]')).toHaveCount(0)
  expect(multipart).toHaveLength(2); expect(multipart[1]).toEqual(multipart[0]); expect(JSON.parse(multipart[0]!.metadata)).toMatchObject({ content: '原始附件消息', expectedDiscussionRevision: 1, expectedDesignRevision: 0 }); expect(JSON.parse(multipart[0]!.metadata).submissionId).toBeTruthy(); expect(multipart[0]!.files).toEqual([{ name: 'original.txt', bytes: Buffer.from('original\u0000bytes\n中文').toString('hex') }])
  await expect(composer).toHaveValue('后来编辑未发送')
  await expect(page.getByText('later.txt · 11 bytes', { exact: true })).toBeVisible(); await record('designer-original-file-recovery.json', { multipart, originalEqual: true }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})
