import { expect, test, type Page, type Route } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { SKIN_STORAGE_KEY } from '../../src/themes/registry'
import { semanticName } from '../w3/semantics'
import { observeW2Resources, assertW2Disposed, type W2ResourceSnapshot } from '../w2/resources'
import { w4ProductFixture, permission, inboxQuestion, taskDto } from './productFixture'
import { evidence, record, stableShot, immediateExit } from './evidence'

const entries = [
  { kind: 'task', path: '/tasks/w4-task', heading: 'w4-task · 核算任务 · 模拟' },
  { kind: 'history', path: '/tasks/w4-task/design', heading: '冻结设计 · w4-task · 模拟' },
  { kind: 'recovery', path: '/tasks/w4-task/recovery', heading: '恢复工作台 · w4-task · 核算任务 · 模拟' },
  { kind: 'inbox', path: '/inbox', heading: '待处理中心' },
] as const
async function prepared(page: Page, skin = 'spdb') {
  await observeW2Resources(page); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin })
}
async function loaded(page: Page, entry: typeof entries[number]) {
  await expect(page.locator('[data-react-page]')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: entry.heading, exact: true })).toBeVisible()
  if (entry.kind === 'inbox') await expect(page.getByText('读取仓库状态 · 模拟', { exact: true })).toBeVisible()
}
/** Warm the runner's own hit-target listeners before any application root exists. */
async function startWithRunnerReady(page: Page, path: string) {
  let release!: () => void
  const hold = new Promise<void>(resolve => { release = resolve })
  const modules = async (route: Route) => { await hold; await route.continue() }
  await page.route('**/assets/*.js', modules)
  try {
    await page.goto(path, { waitUntil: 'commit' })
    await expect(page.locator('#app')).toHaveCount(1)
    await expect(page.locator('[data-react-page]')).toHaveCount(0)
    // Locator.evaluate constructs the main-world runner before root ownership exists.
    await page.locator('html').evaluate(element => element.tagName)
    await page.locator('html').click({ position: { x: 400, y: 100 } })
  } finally { release() }
  await page.unroute('**/assets/*.js', modules)
  await page.waitForLoadState('load')
}
/** Invoke the actual bridge's declared public host API, without library introspection. */
async function disposeActualReactRoot(page: Page) {
  return page.evaluate(() => {
    const host = document.querySelector('[data-app-route-owner]') as import('../../src/migration/reactViewLifecycle').ReactViewHost
    const lifecycle = host?.reactViewLifecycle
    if (!lifecycle || typeof lifecycle.disposeIfSafe !== 'function') throw new Error('Public bridge lifecycle is unavailable')
    const disposed = lifecycle.disposeIfSafe()
    return { disposed, immediate: window.__w2Resources.snapshot() }
  })
}
async function selected(page: Page, kind: typeof entries[number]['kind']) {
  if (kind === 'history') {
    await page.getByRole('button', { name: semanticName('selection.select', '冻结附件清单') }).click()
    await page.locator('[data-semantic="history.previewAttachment"]').click()
    await expect(page.getByText('w4-task 原冻结正文 · 模拟', { exact: true })).toBeVisible()
  }
  if (kind === 'task') {
    const button = page.getByRole('button', { name: semanticName('selection.select', '任务会话') })
    if (await button.count()) await button.click()
    await expect(page.getByRole('region', { name: '任务会话', exact: true })).toBeVisible()
  }
  if (kind === 'inbox') {
    const button = page.getByRole('button', { name: semanticName('selection.select', '读取仓库状态 · 模拟') })
    if (await button.count()) await button.click()
    await expect(page.locator('[data-semantic="inbox.allowOnce"]')).toBeVisible()
  }
}
for (const skin of ['spdb', 'tech-blue', 'github-white']) for (const entry of entries) test(`${skin} actual ${entry.kind} direct/refresh/back/forward and 3 immediate exits`, async ({ page }) => {
  const fixture = await w4ProductFixture(page, { task: entry.kind === 'recovery' ? { status: 'FAILED' } : undefined }); await prepared(page, skin)
  await page.goto(entry.path); await loaded(page, entry); await page.reload(); await loaded(page, entry)
  await stableShot(page, `${skin}-${entry.kind}-default.png`)
  const rounds = []
  for (let cycle = 0; cycle < 3; cycle++) {
    if (cycle) { await page.goBack(); await loaded(page, entry) }
    await selected(page, entry.kind)
    if (!cycle && entry.kind !== 'recovery') await stableShot(page, `${skin}-${entry.kind}-selected.png`)
    if (!cycle && entry.kind === 'task') {
      await page.getByRole('button', { name: semanticName('selection.select', '执行进度') }).click()
      await expect(page.locator('[data-canvas-kind="stages"]')).toBeVisible()
      await stableShot(page, `${skin}-task-progress.png`)
    }
    await page.evaluate(() => window.__w2Resources.begin())
    const before = await page.evaluate(() => window.__w2Resources.snapshot()), streamsBefore = await page.evaluate(() => (window as unknown as { __w2Streams: { opened: string[]; closed: string[] } }).__w2Streams)
    const immediate = await immediateExit(page, '.app-sidebar a[href="/template-tasks"]')
    const streamsAfter = await page.evaluate(() => (window as unknown as { __w2Streams: { opened: string[]; closed: string[] } }).__w2Streams)
    rounds.push({ cycle, before, immediate, streamsBefore, streamsAfter }); await record(`${skin}-${entry.kind}-threecycles.json`, rounds); assertW2Disposed(before, immediate)
    if (entry.kind === 'task') { const url = '/api/tasks/w4-task/events'; expect(streamsBefore.opened.filter(x => x === url).length - streamsBefore.closed.filter(x => x === url).length).toBe(1); expect(streamsAfter.opened.filter(x => x === url)).toHaveLength(streamsAfter.closed.filter(x => x === url).length) }
    expect(streamsAfter.closed.filter(x => x.includes('story-accounting'))).toEqual(streamsBefore.closed.filter(x => x.includes('story-accounting')))
    await expect(page).toHaveURL(/\/template-tasks$/)
  }
  await page.goBack(); await loaded(page, entry); await page.goForward(); await expect(page).toHaveURL(/\/template-tasks$/)
  expect(fixture.requests.filter(row => row.method !== 'GET')).toEqual([]); expect(fixture.base.requests.filter(row => row.method !== 'GET')).toEqual([])
  expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})

for (const entry of entries) test(`actual ${entry.kind} root shutdown immediately releases its reads/listeners/observers`, async ({ page }) => {
  const fixture = await w4ProductFixture(page, { task: entry.kind === 'recovery' ? { status: 'FAILED' } : undefined }); await prepared(page); await page.goto(entry.path); await loaded(page, entry); await selected(page, entry.kind)
  await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot())
  const { disposed, immediate } = await disposeActualReactRoot(page); expect(disposed).toBe(true)
  await record(`${entry.kind}-root-shutdown.json`, { before, immediate }); assertW2Disposed(before, immediate)
  expect(fixture.base.errors).toEqual([])
})

for (const exit of ['route', 'root'] as const) test(`native Task SSE reconnect sends exact Last-Event-ID while REST stays authoritative; ${exit} closes only Task stream`, async ({ page }) => {
  const id = `native-cursor-${exit}`, path = `/api/tasks/${id}/events`
  const fixture = await w4ProductFixture(page, { native: true }); await prepared(page); await page.goto(`/tasks/${id}`); await expect(page.getByRole('heading', { name: `${id} · 核算任务 · 模拟`, exact: true })).toBeVisible()
  const rows = async () => JSON.parse(await readFile(join(evidence, 'native-sse.json'), 'utf8')) as { path: string; cursor: string | null; closed: number | null }[]
  await expect.poll(async () => (await rows()).filter(row => row.path === path).map(row => row.cursor)).toContain('17')
  await expect(page.getByText('运行中', { exact: true }).first()).toBeVisible(); await expect(page.locator('[data-semantic="task.pause"]')).toBeVisible(); await expect(page.locator('[data-semantic="task.start"]')).toHaveCount(0)
  await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot()), streamsBefore = await page.evaluate(() => (window as any).__w2Streams)
  expect(streamsBefore.opened.filter((x: string) => x === path)).toHaveLength(1)
  const immediate = exit === 'route' ? await immediateExit(page, '.app-sidebar a[href="/template-tasks"]') : (await disposeActualReactRoot(page)).immediate; assertW2Disposed(before, immediate)
  const streamsAfter = await page.evaluate(() => (window as any).__w2Streams)
  expect(streamsAfter.closed.filter((x: string) => x === path)).toHaveLength(1)
  await expect.poll(async () => (await rows()).filter(row => row.path === path).every(row => row.closed !== null)).toBe(true)
  const reads = fixture.requests.filter(row => row.path === `/api/tasks/${id}/overview`).length; await page.waitForTimeout(450); expect(fixture.requests.filter(row => row.path === `/api/tasks/${id}/overview`)).toHaveLength(reads)
  await record(`native-sse-authority-exit-${exit}.json`, { streamsBefore, streamsAfter, before, immediate, rows: await rows() }); expect(fixture.unexpected).toEqual([]); expect(fixture.base.errors).toEqual([])
})

test('Task pending/unknown original command blocks route navigation and legal GET recovery never repeats stop', async ({ page }) => {
  let release!: () => void
  const holding = new Promise<void>(resolve => { release = resolve })
  const fixture = await w4ProductFixture(page, { write: async route => { expect(route.request().url()).toContain('/tasks/w4-task/cancel'); await holding; await route.fulfill({ status: 502, json: { detail: '模拟投递回执未知' } }) } }); await prepared(page); await page.goto('/tasks/w4-task'); await loaded(page, entries[0])
  await page.locator('[data-semantic="task.cancel"]').click(); const confirm = page.getByRole('dialog', { name: '取消任务', exact: true }); await confirm.locator('[data-semantic="task.cancel"]').click()
  await expect.poll(() => fixture.requests.filter(row => row.method === 'POST').length).toBe(1)
  await page.evaluate(() => window.__w2Resources.begin()); const disposeAttempt = await disposeActualReactRoot(page); expect(disposeAttempt.disposed).toBe(false); expect(disposeAttempt.immediate.rootConnected).toBe(true)
  await expect(confirm).toBeHidden()
  await expect(page.locator('.ant-modal-wrap:visible')).toHaveCount(0)
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/tasks\/w4-task$/)
  release(); await expect(page.getByText('模拟投递回执未知', { exact: false }).first()).toBeVisible()
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/tasks\/w4-task$/)
  fixture.setTask('w4-task', { status: 'STOPPING', version: 4, updatedAt: '2026-10-03T10:01:00Z' }); await page.locator('[data-semantic="receipt.readOriginal"]').click()
  await expect(page.getByText('正在确认旧会话与进程停止；确认前保留租约。', { exact: false })).toBeVisible(); await expect(page.getByText('已取消', { exact: true })).toHaveCount(0)
  expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1); expect(fixture.base.errors).toEqual([])
})

test('Inbox exact version answer and permission behavior remain explicit; unknown cannot be dismissed', async ({ page }) => {
  const fixture = await w4ProductFixture(page, { write: async route => { expect(route.request().postDataJSON()).toEqual({ action: 'ONCE', version: 4 }); await route.fulfill({ status: 502, json: { detail: '模拟权限回执未知' } }) } }); await prepared(page); await page.goto('/inbox'); await loaded(page, entries[3]); await selected(page, 'inbox')
  await page.locator('[data-semantic="inbox.allowOnce"]').click(); await expect(page.getByText('模拟权限回执未知', { exact: false }).first()).toBeVisible()
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/inbox$/)
  fixture.setInteractions([{ ...permission, version: 5, state: 'RESOLVED', resolvedAction: 'ONCE' }, inboxQuestion]); await page.locator('[data-semantic="receipt.readOriginal"]').click()
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/template-tasks$/)
  expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1); expect(fixture.base.errors).toEqual([])
})

test('PENDING_START only explicit start writes; READY never exposes another start or retries on mount', async ({ page }) => {
  const fixture = await w4ProductFixture(page, { task: { status: 'PENDING_START' }, write: async route => {
    expect(route.request().url()).toContain('/tasks/w4-task/start'); fixture.setTask('w4-task', { status: 'READY', version: 4 }); await route.fulfill({ json: fixture.state('w4-task') })
  } }); await prepared(page); await page.goto('/tasks/w4-task'); await loaded(page, entries[0]); expect(fixture.requests.filter(row => row.method === 'POST')).toEqual([])
  await page.locator('[data-semantic="task.start"]').click(); await expect(page.locator('[data-semantic="task.start"]')).toHaveCount(0)
  await expect(page.getByText('执行请求已接受，系统会自动继续。', { exact: false })).toBeVisible(); await page.reload(); await loaded(page, entries[0]); expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1)
  expect(fixture.base.errors).toEqual([])
})

test('Recovery explicit mode preserves accepted child and uses only legal GET after readback failure', async ({ page }) => {
  let child = false, offline = false
  const draft = { taskId: 'child', parentTaskId: 'w4-task', mode: 'VERIFY_ONLY', parentStageId: 'stage-1', workspaceFingerprint: 'frozenhash', writableSession: false }
  const fixture = await w4ProductFixture(page, { task: { status: 'FAILED' }, write: async route => {
    expect(route.request().postDataJSON()).toEqual({ mode: 'VERIFY_ONLY' }); child = true; offline = true; await route.fulfill({ json: draft })
  }, read: async route => {
    if (route.request().url().endsWith('/recoveries')) { await route.fulfill({ status: offline ? 503 : 200, json: offline ? { detail: '模拟恢复记录读取中断' } : child ? [draft] : [] }); return true } return false
  } }); await prepared(page); await page.goto('/tasks/w4-task/recovery'); await loaded(page, entries[2]); await page.getByRole('radio', { name: '只读验证' }).check(); await page.locator('[data-semantic="task.createRecovery"]').click()
  await expect(page.getByText('模拟恢复记录读取中断', { exact: false }).first()).toBeVisible(); await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/recovery$/)
  offline = false; await page.locator('[data-semantic="receipt.readOriginal"]').click(); await expect(page.getByRole('heading', { name: '恢复草稿已创建' })).toBeVisible(); await page.locator('[data-semantic="task.open"]').first().click(); await expect(page).toHaveURL(/\/tasks\/child$/)
  expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1); expect(fixture.requests.filter(row => row.path.endsWith('/start'))).toEqual([]); expect(fixture.base.errors).toEqual([])
})

test('Recovery unknown keyless create retains original mode; a similar lineage row never proves acceptance', async ({ page }) => {
  let lineage: object[] = []
  const fixture = await w4ProductFixture(page, { task: { status: 'FAILED' }, read: async route => { if (route.request().url().endsWith('/recoveries')) { await route.fulfill({ json: lineage }); return true } return false }, write: async route => { await route.fulfill({ status: 502, json: { detail: '模拟恢复创建回执未知' } }) } }); await prepared(page); await page.goto('/tasks/w4-task/recovery'); await loaded(page, entries[2]); await page.getByRole('radio', { name: '复制全部阶段' }).check(); await page.locator('[data-semantic="task.createRecovery"]').click()
  await expect(page.getByText('模拟恢复创建回执未知', { exact: false }).first()).toBeVisible(); lineage = [{ taskId: 'similar-child', parentTaskId: 'w4-task', mode: 'ALL_STAGES', parentStageId: 'stage-1', workspaceFingerprint: 'same-fingerprint', writableSession: true }]; await page.locator('[data-semantic="receipt.readOriginal"]').click(); await expect(page.getByRole('heading', { name: '派生任务 1', exact: true })).toBeVisible(); await expect(page.getByRole('radio', { name: '复制全部阶段' })).toBeChecked(); await expect(page.getByRole('radio', { name: '只读验证' })).toBeDisabled()
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/recovery$/); expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1)
})

test('Task session answer draft/theme/native blocked refresh/back preserves original input and unknown answer does not replay', async ({ page }) => {
  const fixture = await w4ProductFixture(page, { questions: true, write: async route => { expect(route.request().postDataJSON()).toEqual({ answers: [['原问题的明确回答']] }); await route.fulfill({ status: 502, json: { detail: '模拟回答回执未知' } }) } }); await prepared(page); await page.goto('/template-tasks'); await page.goto('/tasks/w4-task'); await loaded(page, entries[0]); await selected(page, 'task'); await expect(page.getByRole('region', { name: '待回答问题' })).toBeVisible()
  const input = page.getByRole('textbox', { name: '处理方式补充回答' }); await input.fill('原问题的明确回答')
  await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption('tech-blue'); await expect(input).toHaveValue('原问题的明确回答'); expect(fixture.requests.filter(row => row.method === 'POST')).toEqual([])
  await page.locator('[data-semantic="inbox.answer"]').click(); await expect(page.getByText('模拟回答回执未知', { exact: false }).first()).toBeVisible(); await expect(input).toHaveValue('原问题的明确回答'); await expect(input).toBeDisabled()
  await page.locator('[data-semantic="receipt.readOriginal"]').first().click(); fixture.setQuestions(false); await page.locator('[data-semantic="receipt.readOriginal"]').first().click(); await expect(input).toHaveValue('原问题的明确回答')
  const token = await page.evaluate(() => { const root = document.querySelector('[data-react-page]')!, token = crypto.randomUUID(); root.setAttribute('data-test-owner', token); return token })
  let prompts = 0
  page.on('dialog', async dialog => { expect(dialog.type()).toBe('beforeunload'); prompts++; await dialog.dismiss() })
  const expectedBlockedNavigation = (error: Error) => {
    // Chromium dismissed beforeunload retains the document and emits no commit.
    // Only this navigation wait timeout / abort is allowed; the real dialog,
    // unchanged owner token and original draft are checked separately below.
    expect(error instanceof Error).toBe(true)
    expect(error.name === 'TimeoutError' && /page\.(reload|goBack): Timeout 3000ms exceeded/.test(error.message) || /ERR_ABORTED/.test(error.message)).toBe(true)
  }
  await page.reload({ waitUntil: 'commit', timeout: 3000 }).catch(expectedBlockedNavigation)
  await expect.poll(() => prompts).toBe(1); await expect(page.locator('[data-react-page]')).toHaveAttribute('data-test-owner', token); await expect(input).toHaveValue('原问题的明确回答')
  await page.goBack({ waitUntil: 'commit', timeout: 3000 }).catch(expectedBlockedNavigation)
  await expect.poll(() => prompts).toBe(2); await expect(page.locator('[data-react-page]')).toHaveAttribute('data-test-owner', token); await expect(input).toHaveValue('原问题的明确回答')
  await page.locator('.app-sidebar a[href="/template-tasks"]').click(); await expect(page).toHaveURL(/\/tasks\/w4-task$/); expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1); expect(fixture.base.errors).toEqual([])
})

for (const exit of ['route', 'root'] as const) test(`Task readonly stage background active pan ${exit} immediately releases capture/listeners/RAF without mouseup`, async ({ page }) => {
  const fixture = await w4ProductFixture(page); await prepared(page); await startWithRunnerReady(page, '/tasks/w4-task'); await loaded(page, entries[0]); await page.getByRole('button', { name: semanticName('selection.select', '执行进度') }).click(); const canvas = page.locator('[data-canvas-kind="stages"]'), bounds = await canvas.boundingBox(); expect(bounds).toBeTruthy()
  await page.evaluate(() => window.__w2Resources.begin()); await page.mouse.move(bounds!.x + bounds!.width - 60, bounds!.y + bounds!.height - 55); await page.mouse.down(); await page.mouse.move(bounds!.x + bounds!.width - 100, bounds!.y + bounds!.height - 55); await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
  const before = await page.evaluate(() => window.__w2Resources.snapshot()); expect(before.captures.length).toBeGreaterThan(0)
  const immediate = exit === 'route' ? await immediateExit(page, '.app-sidebar a[href="/template-tasks"]') : (await disposeActualReactRoot(page)).immediate
  await record(`active-stage-pan-${exit}.json`, { before, immediate }); assertW2Disposed(before, immediate); await page.mouse.up(); expect(fixture.base.errors).toEqual([])
})

for (const skin of ['spdb', 'tech-blue', 'github-white']) test(`${skin} real publication context dirty close/reduced motion/focus and theme preserve state`, async ({ page }) => {
  const fixture = await w4ProductFixture(page, { task: { status: 'SUCCEEDED' }, write: async route => { expect(route.request().url()).toContain('/publication/commit-message'); await route.fulfill({ json: { subject: '核对已验收接口', aiGenerated: true } }) } }); await prepared(page, skin); await page.goto('/tasks/w4-task'); await loaded(page, entries[0]); const open = page.getByRole('button', { name: semanticName('publication.commit'), exact: true }); await open.click(); const panel = page.getByRole('complementary', { name: '提交任务变更' }); await expect(panel).toBeVisible(); await expect(panel.getByRole('textbox', { name: 'AI 提交说明' })).toHaveValue('核对已验收接口')
  await stableShot(page, `${skin}-publication-selected.png`); await panel.getByRole('textbox', { name: '4 位数字工单号' }).fill('1234'); await page.keyboard.press('Escape'); const dialog = page.getByRole('dialog'); await expect(dialog).toBeVisible(); await dialog.locator('[data-semantic="ui.stay"]').click(); await expect(dialog).toBeHidden(); await expect(page.locator('.ant-modal-wrap:visible')).toHaveCount(0); await expect(panel.getByRole('textbox', { name: '4 位数字工单号' })).toHaveValue('1234')
  await page.emulateMedia({ reducedMotion: 'no-preference' }); await page.getByRole('combobox', { name: semanticName('settings.changeSkin') }).selectOption(skin === 'spdb' ? 'github-white' : 'spdb'); await expect(panel.getByRole('textbox', { name: '4 位数字工单号' })).toHaveValue('1234'); await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.keyboard.press('Escape'); await page.getByRole('dialog').locator('[data-semantic="ui.discardChanges"]').click(); await expect(panel).toBeHidden(); await expect(open).toBeFocused(); expect(fixture.requests.filter(row => row.method === 'POST')).toHaveLength(1); expect(fixture.base.errors).toEqual([])
})
