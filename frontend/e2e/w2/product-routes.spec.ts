import { test, expect, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { SKIN_STORAGE_KEY } from '../../src/themes/registry'
import { productFixture, project, database, conversation, document, role } from './productFixture'
import { observeW2Resources, immediateW2Exit, assertW2Disposed } from './resources'
const evidence = process.env.W2_EVIDENCE_DIR ?? 'test-results/w2-production'
const routes = [
  { path: '/', name: 'home', title: '主页', item: project.name }, { path: '/projects', name: 'projects', title: '项目登记', item: project.name },
  { path: '/ppt', name: 'ppt', title: 'PPT 工作室', item: document.title }, { path: '/knowledge/history', name: 'knowledge-history', title: '历史对话', item: conversation.title },
  { path: '/requirements', name: 'requirements', title: '需求任务', item: '待规划需求 · 模拟' }, { path: '/workflows', name: 'workflows', title: '流程', item: '核对交付流程 · 模拟' },
  { path: '/designs', name: 'designs', title: '历史设计', item: '报表设计方案 · 模拟' }, { path: '/tasks', name: 'tasks', title: '任务控制台', item: '' },
  { path: '/insights', name: 'insights', title: '用量与质量', item: '报表核对任务 · 模拟' }, { path: '/runtime', name: 'runtime', title: 'OpenCode 运行环境', item: '' },
  { path: '/tools', name: 'tools', title: '工具与 Skill', item: '只读搜索工具 · 模拟' }, { path: '/databases', name: 'databases', title: '数据库', item: database.name },
  { path: '/settings', name: 'settings', title: '设置', item: '' }, { path: '/roles', name: 'roles', title: '角色管理', item: role.displayName },
]
async function stableShot(page: Page, filename: string) {
  // Settle actual lazy CSS/data/fonts before sampling. This runs before the
  // cleanup sample and never supplies an input or delay to the disposal gate.
  await page.waitForLoadState('networkidle')
  await page.evaluate(async () => { window.scrollTo(0, 0); await document.fonts.ready; await Promise.all(document.getAnimations().filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime)).map(animation => animation.finished.catch(() => undefined))) })
  const samples: string[] = []; let previous: Buffer | undefined, current: Buffer | undefined
  await expect.poll(async () => {
    previous = current; current = await page.screenshot({ animations: 'disabled' })
    const hash = createHash('sha256').update(current).digest('hex'); samples.push(hash)
    return previous !== undefined && createHash('sha256').update(previous).digest('hex') === hash
  }, { timeout: 5000, intervals: [100, 100, 100] }).toBe(true)
  // No pixel tolerance: the exact two final buffers must be identical.
  expect(createHash('sha256').update(previous!).digest('hex')).toBe(createHash('sha256').update(current!).digest('hex'))
  await mkdir(join(evidence, 'screenshots'), { recursive: true }); await writeFile(join(evidence, 'screenshots', filename), current!)
  await writeFile(join(evidence, 'screenshots', `${filename}.stability.json`), JSON.stringify({ samples, exactFinalPair: true }, null, 2))
}
for (const skin of ['spdb', 'tech-blue', 'github-white']) for (const route of routes) {
  test(`${skin} real W2 ${route.name} desktop 1440/1280 selection and strict SPA exit`, async ({ page }, info) => {
    const fixture = await productFixture(page); await observeW2Resources(page)
    await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.emulateMedia({ reducedMotion: 'reduce' }); await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(route.path)
    const chrome = page.locator('[data-react-page]'); await expect(chrome).toHaveCount(1); await expect(chrome.getByRole('heading', { name: route.title, exact: true })).toBeVisible()
    // Real document reload must resolve the same production history route.
    await page.reload(); await expect(page).toHaveURL(new RegExp(`${route.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)); await expect(chrome.getByRole('heading', { name: route.title, exact: true })).toBeVisible()
    await expect(page.locator('[data-app-route-owner][data-page-runtime="react"]')).toHaveCount(1); await expect(chrome.locator('main#main-content')).toHaveCount(1)
    await expect(page.locator('.app-sidebar')).toHaveCount(1); await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
    // Complete actual reads before recording default, so loading does not substitute for content.
    if (route.item) await expect(chrome.getByRole('button', { name: new RegExp(route.item.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }).first()).toBeVisible()
    else if (route.name === 'tasks') await expect(chrome.getByRole('link', { name: '报表核对任务 · 模拟', exact: true })).toBeVisible()
    else if (route.name === 'runtime') await expect(chrome.getByText('OpenCode fixture-cli')).toBeVisible()
    else await expect(chrome.getByLabel('服务端口（重启生效）')).toBeEnabled()
    await expect(chrome.getByRole('complementary')).toHaveCount(0)
    expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
    await stableShot(page, `${skin}-${route.name}-default.png`)
    await page.setViewportSize({ width: 1280, height: 900 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.evaluate(() => window.__w2Resources.begin())
    if (route.item || route.name === 'tasks') {
      const item = route.name === 'tasks' ? chrome.getByRole('row', { name: /报表核对任务 · 模拟/ }) : chrome.getByRole('button', { name: new RegExp(route.item.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }).first(); await item.focus(); await page.keyboard.press('Enter')
      const panel = chrome.getByRole('complementary'); await expect(panel).toBeVisible()
      if (skin === 'spdb' || route.name === 'tools' && skin === 'tech-blue' || route.name === 'roles' && skin === 'github-white') await stableShot(page, `${skin}-${route.name}-selected.png`)
      await panel.focus(); await page.keyboard.press('Escape'); await expect(panel).toHaveCount(0); await expect(item).toBeFocused()
    }
    const before = await page.evaluate(() => window.__w2Resources.snapshot())
    expect(before.rootConnected).toBe(true); expect(before.sentinel.active).toBe(true)
    expect(before.listeners.some(row => row.type === 'beforeunload' && row.targetKind === 'window')).toBe(true)
    // The sole App FoundationProvider now owns its persistent motion listener.
    // Page listeners still fall under the unchanged strict [] exit gate below.
    expect(before.applicationMediaListeners.some(row => row.type === 'change')).toBe(true)
    const immediate = await immediateW2Exit(page)
    await mkdir(evidence, { recursive: true }); const proof = { route, skin, before, immediate, requests: fixture.requests, streams: await page.evaluate(() => (window as unknown as { __w2Streams: unknown }).__w2Streams), errors: fixture.errors, unexpected: fixture.unexpected }
    await writeFile(join(evidence, `${skin}-${route.name}-firstsnapshot.json`), JSON.stringify(proof, null, 2)); await info.attach('firstsnapshot', { body: JSON.stringify(proof), contentType: 'application/json' })
    assertW2Disposed(before, immediate); expect(immediate.applicationMediaListeners).toEqual(before.applicationMediaListeners); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([])
    // These events occur only after the strict first sample; they cannot clean it.
    await page.keyboard.press('Shift'); const sentinel = await page.evaluate(() => window.__w2Resources.snapshot().sentinel); expect(sentinel.calls).toBeGreaterThan(immediate.sentinel.calls)
    await page.goBack(); await expect(page).toHaveURL(new RegExp(`${route.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)); await expect(chrome.getByRole('heading', { name: route.title, exact: true })).toBeVisible(); await expect(page.locator('[data-app-route-owner][data-page-runtime="react"]')).toHaveCount(1)
    // W3 now renders the destination with React too. The strict first snapshot
    // above still proves the original root was retired, without a cleanup input.
    await page.goForward(); await expect(page).toHaveURL(/\/template-tasks$/)
    await expect(page.locator('[data-react-page="nav.templateTasks"]')).toHaveCount(1)
    await expect(page.getByRole('heading', { name: '任务模板', exact: true })).toBeVisible()
    expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
  })
}

test('PPT real creation UNKNOWN blocks SPA exit and retains body; dirty leave defaults Stay', async ({ page, context }) => {
  const writes: string[] = []
  const fixture = await productFixture(page, async route => { writes.push(route.request().postData() ?? ''); if (new URL(route.request().url()).pathname === '/api/ppt/documents') return route.fulfill({ status: 503, json: { message: '回执未收到，保留原要求' } }); return route.fulfill({ status: 501, json: { message: '未定义写入' } }) })
  await page.goto('/ppt'); await page.locator('#ppt-first-prompt').fill('明确原制作要求'); await page.locator('.app-sidebar a[href="/tools"]').click()
  const dialog = page.getByRole('dialog', { name: '离开当前页面' }); await expect(dialog).toBeVisible(); await expect(dialog.locator('[data-semantic="ui.stay"]')).toBeFocused(); await dialog.locator('[data-semantic="ui.stay"]').click(); await expect(page).toHaveURL(/\/ppt$/)
  await page.locator('[data-react-page] [data-semantic="ppt.send"]').click(); await expect(page.getByRole('status', { name: '' }).filter({ hasText: '原制作要求尚未确认' })).toBeVisible(); expect(writes).toHaveLength(1)
  await page.locator('.app-sidebar a[href="/tools"]').click(); await expect(page).toHaveURL(/\/ppt$/); await expect(page.locator('#ppt-first-prompt')).toHaveValue('明确原制作要求'); await expect(page.locator('#ppt-first-prompt')).toHaveAttribute('readonly', '')
  const original = await page.locator('[data-react-page]').evaluate(element => { const token = crypto.randomUUID(); element.setAttribute('data-test-instance', token); return { token, metadata: sessionStorage.getItem('loopper.ppt.creation.v2') } })
  await page.evaluate(() => { const audit = [] as { trusted: boolean; key: string | null; value: string | null }[]; (window as unknown as { __w2SkinEvents: typeof audit }).__w2SkinEvents = audit; window.addEventListener('storage', event => { audit.push({ trusted: event.isTrusted, key: event.key, value: event.newValue }) }) })
  const settings = await context.newPage(), settingsFixture = await productFixture(settings); await settings.goto('/settings')
  for (const skin of ['tech-blue', 'github-white', 'spdb']) {
    await settings.getByRole('combobox', { name: /皮肤/ }).selectOption(skin)
    await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
    await expect(page.locator('[data-react-page]')).toHaveAttribute('data-test-instance', original.token)
    await expect(page.locator('#ppt-first-prompt')).toHaveValue('明确原制作要求'); await expect(page.locator('#ppt-first-prompt')).toHaveAttribute('readonly', '')
    await expect(page.getByRole('status').filter({ hasText: '原制作要求尚未确认' })).toBeVisible()
    expect(await page.evaluate(() => sessionStorage.getItem('loopper.ppt.creation.v2'))).toBe(original.metadata); expect(writes).toHaveLength(1)
  }
  const storageEvents = await page.evaluate(() => (window as unknown as { __w2SkinEvents: { trusted: boolean; key: string | null; value: string | null }[] }).__w2SkinEvents)
  for (const skin of ['tech-blue', 'github-white', 'spdb']) expect(storageEvents.some(event => event.trusted && event.key === SKIN_STORAGE_KEY && event.value === skin)).toBe(true)
  expect(settingsFixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(settingsFixture.errors).toEqual([]); expect(settingsFixture.unexpected).toEqual([]); await settings.close(); await page.bringToFront()
  await stableShot(page, 'spdb-ppt-unknown.png'); expect(writes).toHaveLength(1); expect(fixture.errors).toEqual([])
  await writeFile(join(evidence, 'ppt-unknown-native-storage.json'), JSON.stringify({ storageEvents, preserved: true, writes: writes.length }, null, 2))
})

test('Knowledge real same-instance query/back synchronization', async ({ page }) => {
  const fixture = await productFixture(page); await page.goto('/knowledge/history?query=原筛选'); const original = await page.locator('[data-react-page]').evaluate(element => { element.setAttribute('data-test-identity', 'original'); return 'original' })
  await expect(page.getByLabel('搜索历史对话')).toHaveValue('原筛选'); await page.getByLabel('搜索历史对话').fill('新筛选'); await expect(page).toHaveURL(/query=%E6%96%B0%E7%AD%9B%E9%80%89/)
  await expect(page.locator('[data-react-page]')).toHaveAttribute('data-test-identity', original)
  await page.locator('.app-sidebar a[href="/tools"]').click(); await expect(page).toHaveURL(/\/tools$/); await page.goBack(); await expect(page.getByLabel('搜索历史对话')).toHaveValue('新筛选')
  expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
})

for (const skin of ['spdb', 'tech-blue', 'github-white']) test(`${skin} real Roles active readonly pan exits immediately for three cycles`, async ({ page }, info) => {
  const fixture = await productFixture(page); await observeW2Resources(page)
  await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await page.goto('/roles')
  const records = []
  for (let cycle = 0; cycle < 3; cycle++) {
    if (cycle) { await page.locator('.app-sidebar a[href="/roles"]').click(); await expect(page.locator('[data-react-page]')).toHaveCount(1) }
    await page.locator('[data-react-page]').getByRole('button', { name: new RegExp(role.displayName) }).click()
    const panel = page.locator('[data-react-page]').getByRole('complementary'); await panel.locator('[data-semantic="ui.expand"]').click()
    const diagram = page.locator('.readonly-diagram').first()
    // Scroll in the page without adopting an ElementHandle into the main world.
    await page.evaluate(() => { document.querySelector('.readonly-diagram')!.scrollIntoView({ block: 'center', behavior: 'instant' }) })
    await expect(diagram).toBeVisible(); await expect(diagram.locator('.react-flow__node').first()).toBeVisible()
    // Return only plain measurements: Locator.evaluate creates a main-world
    // ElementHandle whose preview initializes Playwright's global interceptors.
    const at = await page.evaluate(() => {
      const element = document.querySelector('.readonly-diagram')!
      const pane = element.querySelector('.react-flow__pane')!, box = pane.getBoundingClientRect()
      const left = Math.ceil(Math.max(0, box.left)) + 4, right = Math.floor(Math.min(innerWidth, box.right)) - 4
      const top = Math.ceil(Math.max(0, box.top)) + 4, bottom = Math.floor(Math.min(innerHeight, box.bottom)) - 4
      for (let y = top; y + 20 <= bottom; y += 2) for (let x = left; x + 105 <= right; x += 2) {
        if (document.elementFromPoint(x, y) !== pane || document.elementFromPoint(x + 105, y + 20) !== pane) continue
        if (Array.from({ length: 106 }, (_, step) => document.elementFromPoint(x + step, y + 20 * step / 105) === pane).every(Boolean)) return { x, y, paneBox: { x: box.x, y: box.y, width: box.width, height: box.height }, testedPath: 106 }
      }
      throw new Error(`No unobstructed visible 105/20 Roles pan path in ${JSON.stringify({ left, right, top, bottom })}`)
    })
    const start = await page.evaluate(() => { const element = document.querySelector('.readonly-diagram .react-flow__viewport')!; const value = new DOMMatrix(getComputedStyle(element).transform); return { x: value.e, y: value.f } })
    await page.evaluate(() => window.__w2Resources.begin()); await page.mouse.move(at.x, at.y); await page.mouse.down(); await page.mouse.move(at.x + 105, at.y + 20)
    await expect(diagram).toHaveAttribute('data-pointer-gesture', 'pan')
    await expect.poll(async () => page.evaluate(() => { const element = document.querySelector('.readonly-diagram .react-flow__viewport')!; const value = new DOMMatrix(getComputedStyle(element).transform); return { x: value.e, y: value.f } })).toEqual({ x: start.x + 105, y: start.y + 20 })
    const before = await page.evaluate(() => window.__w2Resources.snapshot()); expect(before.captures).toHaveLength(1); expect(before.rootConnected).toBe(true)
    const immediate = await immediateW2Exit(page); records.push({ cycle, at, start, translated: { x: start.x + 105, y: start.y + 20 }, before, immediate }); await mkdir(evidence, { recursive: true }); await writeFile(join(evidence, `${skin}-roles-active-threecycles.json`), JSON.stringify(records, null, 2)); assertW2Disposed(before, immediate)
    // Reset the actual device only AFTER the strict first sample and its no-input assertion.
    await page.mouse.up(); await expect(page.getByRole('heading', { name: '任务模板', exact: true })).toBeVisible()
  }
  await info.attach('threecycles', { body: JSON.stringify(records), contentType: 'application/json' }); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
})

test('Database real accepted save/read failure preserves secret draft and only re-reads', async ({ page }) => {
  let saved = false, failRead = false; const bodies: object[] = [], readbacks: number[] = []
  const fixture = await productFixture(page, async route => { expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); bodies.push(route.request().postDataJSON()); saved = true; failRead = true; return route.fulfill({ json: { ...database, version: 5 } }) })
  await page.route('**/api/database-connections?**', async route => {
    if (!saved) return route.fallback()
    if (failRead) { failRead = false; readbacks.push(503); return route.fulfill({ status: 503, json: { message: '保存已收到，列表读取失败' } }) }
    readbacks.push(200); return route.fulfill({ json: { items: [{ ...database, version: 5 }], facets: {}, nextCursor: null } })
  })
  await page.goto('/databases'); await page.locator('[data-react-page]').getByRole('button', { name: new RegExp(database.name) }).click(); await page.locator('[data-semantic="database.edit"]').click(); await page.getByLabel('新密码').fill(' local secret '); await page.locator('[data-react-page] [data-semantic="ui.save"]').click()
  await expect(page.locator('[data-semantic="receipt.readOriginal"]')).toBeVisible(); await expect(page.getByLabel('新密码')).toHaveValue(' local secret '); await expect(page.getByLabel('新密码')).toBeDisabled(); await page.locator('.app-sidebar a[href="/tools"]').click(); await expect(page).toHaveURL(/\/databases$/)
  await page.locator('[data-semantic="receipt.readOriginal"]').click(); await expect(page.locator('[data-react-page]').getByRole('complementary')).toHaveCount(0); expect(readbacks).toEqual([503, 200]); expect(bodies).toHaveLength(1); expect(bodies[0]).toMatchObject({ password: ' local secret ', version: 4 })
  await page.locator('.app-sidebar a[href="/tools"]').click(); await expect(page).toHaveURL(/\/tools$/); expect(bodies).toHaveLength(1)
  // The synthetic secret is intentionally not written to evidence or screenshots.
  expect(fixture.errors).toEqual([])
})

test('Settings real dirty route confirmation stays then explicitly discards without PUT', async ({ page }) => {
  const fixture = await productFixture(page); await page.goto('/settings'); const input = page.getByLabel('服务端口（重启生效）'); await expect(input).toBeEnabled(); await input.fill('8089')
  const identity = await page.locator('[data-react-page]').evaluate(element => { const value = crypto.randomUUID(); element.setAttribute('data-test-instance', value); return value })
  for (const skin of ['tech-blue', 'github-white', 'spdb']) {
    await page.getByRole('combobox', { name: /皮肤/ }).selectOption(skin); await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
    await expect(page.locator('[data-react-page]')).toHaveAttribute('data-test-instance', identity); await expect(input).toHaveValue('8089'); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([])
  }
  await stableShot(page, 'spdb-settings-dirty.png'); await page.locator('.app-sidebar a[href="/tools"]').click()
  const dialog = page.getByRole('dialog', { name: '离开当前页面' }); await expect(dialog).toBeVisible(); await expect(dialog.locator('[data-semantic="ui.stay"]')).toBeFocused(); await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0); await expect(input).toHaveValue('8089'); await expect(page).toHaveURL(/\/settings$/)
  await page.locator('.app-sidebar a[href="/tools"]').click(); await expect(dialog).toBeVisible(); await dialog.locator('[data-semantic="ui.discardChanges"]').click(); await expect(page).toHaveURL(/\/tools$/); expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([])
})
