import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from '../../node_modules/playwright/index.mjs'
import { expect } from '../../node_modules/@playwright/test/index.mjs'

// Independent desktop browser review. Connects to the lead-owned, React-only fixture.
// Public audit reset creates explicit mock scenarios; business interaction uses real DOM controls.
const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../../..')
const url = process.env.W1_FOUNDATION_URL ?? 'http://127.0.0.1:41784/e2e/w1/preview.html'
assert.equal(new URL(url).hostname, '127.0.0.1')
const raw = resolve(process.env.W1_FOUNDATION_EVIDENCE_DIR ?? join(repo, '../react-full-w1-evidence/browser'))
// Later waves keep the original W1 screenshot bytes while rechecking shared UI.
const shots = resolve(process.env.W1_FOUNDATION_SCREENSHOT_DIR ?? join(here, 'screenshots'))
await Promise.all([mkdir(raw, { recursive: true }), mkdir(shots, { recursive: true })])
await mkdir(join(raw, 'screenshots'), { recursive: true })
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
async function sources() {
  const paths = ['frontend/e2e/w1/verify-foundation.mjs', 'frontend/e2e/w1/preview.tsx', 'frontend/e2e/w1/preview.html', 'frontend/e2e/w1/vite.config.ts',
    'frontend/package-lock.json', 'scripts/generate-foundation-icons.mjs']
  async function walk(dir) { for (const entry of await readdir(join(repo, dir), { withFileTypes: true })) {
    const path = `${dir}/${entry.name}`
    if (entry.isDirectory()) await walk(path)
    else paths.push(path)
  } }
  await walk('frontend/src/foundation')
  return Object.fromEntries(await Promise.all(paths.sort().map(async path => [path, sha(await readFile(join(repo, path)))])))
}
const beforeSources = await sources()
const semanticCatalogue = JSON.parse(await readFile(join(repo, 'frontend/src/foundation/semantic-registry-data.json'), 'utf8'))
const rows = [], resourceProofs = [], screenshots = [], requests = [], websockets = [], errors = [], warnings = [], stateProofs = []
const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROME_EXECUTABLE ?? '/usr/bin/chromium', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, reducedMotion: 'no-preference' })
await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
const page = await context.newPage()
page.on('pageerror', error => errors.push({ kind: 'pageerror', message: error.message }))
page.on('websocket', socket => {
  const address = new URL(socket.url())
  const row = { protocol: address.protocol, hostname: address.hostname, port: address.port, pathname: address.pathname, closed: false }
  websockets.push(row); socket.on('close', () => { row.closed = true })
})
page.on('console', message => {
  if (message.type() === 'error') errors.push({ kind: 'console', message: message.text() })
  else if (message.type() === 'warning') warnings.push(message.text())
})
await page.route('**/*', async route => {
  const request = route.request(), parsed = new URL(request.url())
  requests.push({ method: request.method(), url: request.url(), type: request.resourceType() })
  if (parsed.origin !== new URL(url).origin || request.method() !== 'GET' || parsed.pathname.startsWith('/api/')) {
    errors.push({ kind: 'resource-policy', message: request.url() }); await route.abort(); return
  }
  await route.continue()
})
await page.addInitScript(() => {
  // Observe exact API registrations. Never clear another owner's resources.
  // Controller RAF/interval attribution is an allocation stack from this locked fixture;
  // all ResizeObserver target sets are kept raw, including detached elements.
  const ids = new WeakMap(), callbacks = new WeakMap(), listeners = [], intervals = new Map(), frames = new Map(), allFrames = new Map(), observers = []
  let nextId = 0, nextCallback = 0, setups = 0, sentinel = 0
  const id = target => { if (!ids.has(target)) ids.set(target, ++nextId); return ids.get(target) }
  const callbackId = callback => { if (!callbacks.has(callback)) callbacks.set(callback, ++nextCallback); return callbacks.get(callback) }
  const targetInfo = target => ({ id: id(target), kind: target === window ? 'window' : target === document ? 'document' : target instanceof Element ? target.tagName : target.constructor?.name,
    elementId: target instanceof Element ? target.id : null, connected: target instanceof Element ? target.isConnected : null })
  const ownedStack = stack => stack.includes('/e2e/w1/preview.tsx') && /attachResources|tick/.test(stack)
  const add = EventTarget.prototype.addEventListener, remove = EventTarget.prototype.removeEventListener
  EventTarget.prototype.addEventListener = function(type, callback, options) {
    const stack = new Error().stack ?? '', capture = typeof options === 'boolean' ? options : !!options?.capture
    if (callback && (type === 'w1-instance-probe' || (type === 'change' && this instanceof MediaQueryList)
      || (this === window && (type === 'focusin' || type === 'keydown')))) {
      if (!listeners.some(row => row.active && row.target === this && row.callback === callback && row.type === type && row.capture === capture))
        listeners.push({ target: this, callback, callbackId: callbackId(callback), type, capture, stack, active: true })
    }
    return add.call(this, type, callback, options)
  }
  EventTarget.prototype.removeEventListener = function(type, callback, options) {
    const capture = typeof options === 'boolean' ? options : !!options?.capture
    for (const row of listeners) if (row.target === this && row.callback === callback && row.type === type && row.capture === capture) row.active = false
    return remove.call(this, type, callback, options)
  }
  const request = window.requestAnimationFrame.bind(window), cancel = window.cancelAnimationFrame.bind(window)
  window.requestAnimationFrame = callback => {
    const stack = new Error().stack ?? '', owned = ownedStack(stack)
    let frame
    frame = request(time => { frames.delete(frame); allFrames.delete(frame); callback(time) })
    allFrames.set(frame, { id: frame, callbackId: callbackId(callback), name: callback.name, stack, controllerOwned: owned })
    if (owned) frames.set(frame, { id: frame, callbackId: callbackId(callback), stack })
    return frame
  }
  window.cancelAnimationFrame = frame => { frames.delete(frame); allFrames.delete(frame); return cancel(frame) }
  const interval = window.setInterval.bind(window), clear = window.clearInterval.bind(window)
  window.setInterval = (callback, delay, ...args) => {
    const value = interval(callback, delay, ...args), stack = new Error().stack ?? ''
    if (ownedStack(stack)) intervals.set(value, { id: value, callbackId: callbackId(callback), delay, stack })
    return value
  }
  window.clearInterval = value => { intervals.delete(value); return clear(value) }
  const NativeResizeObserver = window.ResizeObserver
  window.ResizeObserver = class extends NativeResizeObserver {
    constructor(callback) { super(callback); const row = { id: id(this), targets: new Set(), stack: new Error().stack ?? '', disconnects: 0 }; observers.push(row); this.__auditRow = row }
    observe(target, options) { this.__auditRow.targets.add(target); if (target.id === 'w1-root') setups++; return super.observe(target, options) }
    unobserve(target) { this.__auditRow.targets.delete(target); return super.unobserve(target) }
    disconnect() { this.__auditRow.targets.clear(); this.__auditRow.disconnects++; return super.disconnect() }
  }
  add.call(document, 'w1-independent-sentinel', () => { sentinel++ })
  window.__w1Resources = {
    snapshot: () => ({ setups, sentinel,
      listeners: listeners.filter(row => row.active).map(row => ({ callbackId: row.callbackId, type: row.type, capture: row.capture, target: targetInfo(row.target), stack: row.stack })),
      pendingFrames: [...frames.values()], rawPendingFrames: [...allFrames.values()], intervals: [...intervals.values()],
      observers: observers.filter(row => row.targets.size).map(row => ({ id: row.id, targets: [...row.targets].map(targetInfo), stack: row.stack })),
      observerLedger: observers.map(row => ({ id: row.id, activeTargets: row.targets.size, disconnects: row.disconnects, stack: row.stack })),
    }),
  }
})
const audit = () => page.evaluate(() => window.__foundationAudit.snapshot())
const action = key => page.locator(`button[data-semantic="${key}"]`)
const panel = page.locator('[data-foundation-component="context"]')
const modal = page.getByRole('dialog')
async function reset(scenario = 'clean', skin = 'spdb', reduced) {
  await page.evaluate(async ({ scenario, skin, reduced }) => window.__foundationAudit.reset(scenario, skin, reduced), { scenario, skin, reduced })
  await expect(page.locator('.loopper-foundation')).toHaveAttribute('data-foundation-skin', skin)
  await expect.poll(async () => (await audit()).viewCount).toBe(1)
  if (scenario !== 'clean') await expect(panel).toBeVisible()
}
async function snap(label) { const snapshot = await audit(); stateProofs.push({ label, snapshot }); return snapshot }
async function check(name, fn) {
  try { await fn(); rows.push({ name, result: 'PASS' }); process.stdout.write(`PASS ${name}\n`) }
  catch (error) {
    const number = rows.length + 1
    rows.push({ name, result: 'FAIL', error: error.stack ?? String(error) })
    await writeFile(join(raw, `failure-${number}.html`), await page.content()).catch(() => {})
    await page.screenshot({ path: join(raw, `failure-${number}.png`), fullPage: true }).catch(() => {})
    process.stdout.write(`FAIL ${name}: ${error.message}\n`)
  }
}
async function shot(name) {
  await page.evaluate(() => { window.scrollTo(0, 0) })
  let previous, bytes, samples = 0
  for (; samples < 12; samples++) {
    bytes = await page.screenshot({ fullPage: true })
    if (previous && sha(previous) === sha(bytes)) break
    previous = bytes; await page.waitForTimeout(100)
  }
  assert.ok(samples < 12, 'Screenshot did not reach two identical complete frames')
  await writeFile(join(shots, `${name}.png`), bytes)
  await writeFile(join(raw, 'screenshots', `${name}.png`), bytes)
  screenshots.push({ path: `screenshots/${name}.png`, sha256: sha(bytes), bytes: bytes.length, stableSamples: samples + 1, scroll: await page.evaluate(() => ({ x: scrollX, y: scrollY })) })
}
async function expectFocused(locator) { await expect(locator).toBeFocused() }
async function phase(value) { await expect(page.getByTestId('receipt-phase')).toHaveText(value) }
async function recoveryAction(key, label, scope) {
  const entry = semanticCatalogue.actions[key]
  assert.equal(entry.label, label); assert.equal(entry.name, label); assert.equal(entry.scope, scope)
  await expect(action(key)).toHaveAttribute('aria-label', label); await expect(action(key)).toHaveText(label)
  await expect(action('ui.retry')).toHaveCount(0)
  stateProofs.push({ label: 'recovery-action-semantic-scope', snapshot: { key, label, scope, before: await audit() } })
  return action(key)
}
async function resourcesGone(label) {
  const first = await page.evaluate(() => {
    const before = { audit: window.__foundationAudit.snapshot(), resources: window.__w1Resources.snapshot() }
    window.__foundationAudit.unmount()
    return { before, immediate: { audit: window.__foundationAudit.snapshot(), resources: window.__w1Resources.snapshot(), rootChildren: document.getElementById('w1-root').childElementCount } }
  })
  resourceProofs.push({ label, ...first })
  assert.equal(first.before.audit.viewCount, 1)
  assert.equal(first.before.audit.owned.listeners, 1); assert.equal(first.before.resources.pendingFrames.length, 1)
  assert.equal(first.before.resources.intervals.length, 1)
  assert.ok(first.before.resources.observers.some(row => row.targets.some(target => target.elementId === 'w1-root')))
  assert.equal(first.immediate.rootChildren, 0); assert.equal(first.immediate.audit.viewCount, 0)
  for (const key of ['listeners', 'raf', 'timers', 'observers']) assert.equal(first.immediate.audit.owned[key], 0)
  for (const key of ['listeners', 'pendingFrames', 'rawPendingFrames', 'intervals', 'observers']) assert.deepEqual(first.immediate.resources[key], [])
  // The cleanup gate above is the first synchronous sample; these probes cannot make it green.
  const post = await page.evaluate(() => {
    const before = window.__foundationAudit.snapshot().owned.callbacks
    document.dispatchEvent(new Event('w1-instance-probe'))
    document.dispatchEvent(new Event('w1-independent-sentinel'))
    return { before, after: window.__foundationAudit.snapshot().owned.callbacks, sentinel: window.__w1Resources.snapshot().sentinel }
  })
  assert.equal(post.before, post.after); assert.equal(post.sentinel, first.immediate.resources.sentinel + 1)
}

let executionComplete = false
try {
  await page.goto(url, { waitUntil: 'networkidle' })
  await page.waitForFunction(() => !!window.__foundationAudit)
  for (const skin of ['spdb', 'tech-blue', 'github-white']) for (const width of [1440, 1280]) {
    await page.setViewportSize({ width, height: width === 1440 ? 960 : 900 })
    await check(`${skin}/${width}: actual React/Ant eight controlled primitives; clean render has no write`, async () => {
      await reset('clean', skin)
      const snapshot = await snap(`${skin}-${width}-clean`)
      assert.equal(snapshot.writes, 0); assert.equal(snapshot.reads, 0); assert.equal(snapshot.navigations, 0)
      assert.equal(snapshot.phase, 'IDLE'); assert.equal(snapshot.policy.kind, 'ALLOW')
      await expect(panel).toBeHidden(); await expect(page.locator('.ant-table')).toBeVisible()
      for (const component of ['shell', 'action', 'context', 'list', 'table', 'disclosure']) {
        if (component === 'table') assert.ok(await page.locator('.ui-selectable-table.ant-table-wrapper').count())
        else assert.ok(await page.locator(`[data-foundation-component="${component}"]`).count(), component)
      }
      assert.ok(await page.locator('.ui-field.ant-form-item').count())
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
      if (width === 1440) await shot(`${skin}-default`)
    })
    await check(`${skin}/${width}: real keyboard list/table selection, Escape/close return focus, disclosure and nonmodal Tab`, async () => {
      await reset('clean', skin)
      const trigger = page.getByTestId('select-trigger')
      await trigger.focus(); await page.keyboard.press('Enter'); await expect(panel).toBeVisible(); await expectFocused(panel)
      await expect(page.locator('.ui-selectable-list button').first()).toHaveAttribute('aria-pressed', 'true')
      await expect(page.locator('.ant-table-row').first()).toHaveAttribute('aria-selected', 'true')
      await page.keyboard.press('Tab'); assert.ok(await page.evaluate(() => !!document.activeElement.closest('.ui-context-panel')))
      await panel.focus(); await page.keyboard.press('Escape'); await expect(panel).toBeHidden(); await expectFocused(trigger)
      const listSecond = page.locator('.ui-selectable-list button').nth(1)
      await listSecond.focus(); await page.keyboard.press('Space'); await expect(panel).toBeVisible()
      await expect(listSecond).toHaveAttribute('aria-pressed', 'true')
      await action('ui.close').click(); await expect(panel).toBeHidden(); await expectFocused(trigger)
      const row = page.locator('.ant-table-row').first()
      await row.focus(); await page.keyboard.press('Enter'); await expect(panel).toBeVisible()
      await action('ui.expand').filter({ has: page.locator('[data-semantic-icon="ui.expand"]') }).first().click()
      await expect(panel).toHaveClass(/ui-context-expanded/)
      await action('ui.collapse').first().click(); await expect(panel).not.toHaveClass(/ui-context-expanded/)
      const disclosure = page.locator('.ui-disclosure button')
      await disclosure.focus(); await page.keyboard.press('Space'); await expect(disclosure).toHaveAttribute('aria-expanded', 'true')
      await expect(page.locator('.ui-disclosure > div')).toBeVisible()
      await page.keyboard.press('Space'); await expect(page.locator('.ui-disclosure > div')).toBeHidden()
      await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => !!document.activeElement.closest('.ui-context-panel')), false)
      assert.equal((await audit()).writes, 0)
      if (width === 1440) await shot(`${skin}-selected`)
    })
  }
  await page.setViewportSize({ width: 1440, height: 960 })
  await check('theme switching keeps actual input element, controller/body/key and single resource lease', async () => {
    await reset('dirty')
    await page.locator('#draft-title').fill('主题切换后的真实草稿')
    await page.evaluate(() => { window.__w1InputBefore = document.getElementById('draft-title') })
    const before = await audit(), setup = await page.evaluate(() => window.__w1Resources.snapshot().setups)
    for (const skin of ['tech-blue', 'github-white', 'spdb']) {
      await page.locator('#skin').selectOption(skin)
      await expect(page.locator('.loopper-foundation')).toHaveAttribute('data-foundation-skin', skin)
      assert.equal(await page.evaluate(() => window.__w1InputBefore === document.getElementById('draft-title')), true)
      await expect(page.locator('#draft-title')).toHaveValue('主题切换后的真实草稿')
      const after = await audit(); assert.equal(after.key, before.key); assert.deepEqual(after.body, before.body)
      assert.equal(after.writes, 0); assert.equal(after.viewCount, 1)
      assert.equal(await page.evaluate(() => window.__w1Resources.snapshot().setups), setup)
    }
  })
  await check('visible semantic SVG bodies match locked local subset; fields and skip-link have real accessible relationships', async () => {
    await reset('dirty')
    const catalogue = JSON.parse(await readFile(join(repo, 'frontend/src/foundation/semantic-registry-data.json'), 'utf8'))
    const glyphs = JSON.parse(await readFile(join(repo, 'frontend/src/foundation/semantic-glyphs.json'), 'utf8'))
    const proof = await page.evaluate(({ catalogue, glyphs }) => {
      return [...document.querySelectorAll('svg[data-semantic-icon]')].map(svg => {
        const key = svg.dataset.semanticIcon, entry = catalogue.actions[key] ?? catalogue.objects[key]
        const expected = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
        expected.innerHTML = glyphs.glyphs[entry.icon].body
        return { key, sameBody: svg.innerHTML === expected.innerHTML, hidden: svg.getAttribute('aria-hidden'), focusable: svg.getAttribute('focusable') }
      })
    }, { catalogue, glyphs })
    assert.deepEqual(proof.map(icon => icon.key).sort(), ['app.workspace', 'nav.back', 'status.dirty', 'object.project', 'object.project', 'ui.expand', 'ui.close', 'ui.save', 'ui.delete', 'ui.expand'].sort())
    for (const icon of proof) { assert.equal(icon.sameBody, true, icon.key); assert.equal(icon.hidden, 'true'); assert.equal(icon.focusable, 'false') }
    await expect(page.getByLabel('标题', { exact: true })).toHaveAttribute('id', 'draft-title')
    await expect(page.locator('#draft-title')).toHaveAttribute('aria-describedby', 'draft-title-help')
    await expect(page.locator('#draft-title')).toHaveAttribute('required', '')
    const skip = page.locator('.ui-skip-link'); await skip.focus(); await page.keyboard.press('Enter')
    await expect(page.locator('.ui-shell-main')).toBeFocused()
    stateProofs.push({ label: 'semantic-svg-dom-and-accessibility', snapshot: proof })
  })
  await check('removed context trigger returns focus to this provider main; enabled icon-only names/tooltips agree', async () => {
    await reset('clean'); await page.getByTestId('select-trigger').click(); await expect(panel).toBeVisible()
    for (const key of ['ui.expand', 'ui.close']) {
      const control = panel.locator(`[data-semantic="${key}"]`).first()
      assert.equal(await control.getAttribute('title'), await control.getAttribute('aria-label'))
    }
    await page.evaluate(() => window.__foundationAudit.hideTrigger()); await expect(page.getByTestId('select-trigger')).toHaveCount(0)
    await action('ui.close').click(); await expect(panel).toBeHidden(); await expect(page.locator('.ui-shell-main')).toBeFocused()
    assert.equal((await audit()).writes, 0)
  })
  await check('ordinary dirty close: real modal starts at Stay, traps Tab, Escape retains draft, explicit discard alone clears', async () => {
    await reset('clean')
    await page.getByTestId('select-trigger').click(); await page.locator('#draft-title').fill('普通未保存正文')
    await action('ui.close').click(); await expect(modal).toBeVisible(); await expectFocused(modal.locator('[data-semantic="ui.stay"]'))
    assert.equal(await modal.evaluate(element => element.closest('.loopper-foundation') !== null), true)
    const focusProof = []
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab')
      const focus = await page.evaluate(() => ({ tag: document.activeElement.tagName, semantic: document.activeElement.dataset.semantic,
        insideDialog: !!document.activeElement.closest('[role="dialog"]'), insideWrap: !!document.activeElement.closest('.ant-modal-wrap'), documentHasFocus: document.hasFocus() }))
      focusProof.push(focus); stateProofs.push({ label: `modal-tab-${i + 1}`, snapshot: focus })
      assert.equal(focus.insideDialog, true)
    }
    await page.keyboard.press('Escape'); await expect(modal).toBeHidden(); await expect(panel).toBeVisible()
    await expect(page.locator('#draft-title')).toHaveValue('普通未保存正文')
    assert.equal((await audit()).writes, 0); assert.equal((await audit()).navigations, 0)
    await action('ui.close').click(); await expect(modal).toBeVisible(); await shot('spdb-dirty-confirm')
    await modal.locator('[data-semantic="ui.discardChanges"]').click(); await expect(panel).toBeHidden()
    await expectFocused(page.getByTestId('select-trigger')); assert.equal((await audit()).view.dirty, false)
  })
  await check('dirty navigation is explicit; Stay retains owner, discard navigates once without browser history', async () => {
    await reset('dirty'); const originalUrl = page.url()
    await action('nav.back').click(); await expect(modal).toBeVisible()
    await modal.locator('[data-semantic="ui.stay"]').click(); await expect(modal).toBeHidden()
    assert.equal((await audit()).navigations, 0); assert.equal((await audit()).view.dirty, true)
    await action('nav.back').click(); await expect(modal).toBeVisible()
    await modal.locator('[data-semantic="ui.discardChanges"]').click(); await expect.poll(async () => (await audit()).navigations).toBe(1)
    assert.equal(page.url(), originalUrl); assert.equal((await audit()).writes, 0)
  })
  await check('dialog dynamically becoming pending blocks confirmation; controlled external owner intent is explicit', async () => {
    await reset('dirty'); await action('nav.back').click(); await expect(modal).toBeVisible()
    // This models an externally initiated owner command, not an inaccessible click behind the modal.
    await page.evaluate(() => { window.__foundationAudit.holdNextWrite(); void window.__foundationAudit.save() })
    await phase('SENDING'); await expect(modal.locator('[data-semantic="ui.discardChanges"]')).toBeDisabled()
    assert.equal((await audit()).policy.kind, 'BLOCK'); assert.equal((await audit()).writes, 1)
    await expect(modal.getByRole('alert')).toContainText('操作结果尚未确认')
    assert.equal((await audit()).navigations, 0); assert.equal((await audit()).view.dirty, true)
    await modal.locator('[data-semantic="ui.stay"]').click(); await expect(modal).toBeHidden()
    await action('nav.back').click(); await expect(modal).toBeHidden(); assert.equal((await audit()).navigations, 0)
    await snap('live-dialog-policy-pending'); await page.evaluate(() => window.__foundationAudit.finishPending()); await phase('SETTLED')
  })
  for (const scenario of ['pending', 'unknown', 'accepted', 'read-only', 'malformed']) {
    await check(`${scenario}: real disabled fields/save/close, Escape and Back preserve unresolved identity/status`, async () => {
      await reset(scenario, scenario === 'unknown' ? 'tech-blue' : 'spdb')
      const expected = scenario === 'pending' ? 'SENDING' : ['accepted', 'malformed'].includes(scenario) ? 'ACCEPTED_READBACK' : 'UNKNOWN'
      await phase(expected); const before = await snap(`${scenario}-before-guards`)
      await expect(page.locator('#draft-title')).toBeDisabled(); await expect(action('ui.save')).toBeDisabled()
      await expect(action('ui.close')).toBeDisabled(); await expect(action('ui.delete')).toBeDisabled()
      await panel.focus(); await page.keyboard.press('Escape'); await expect(panel).toBeVisible(); await expect(modal).toBeHidden()
      await action('nav.back').click(); await expect(page.getByTestId('critical-status')).toBeVisible(); await expect(modal).toBeHidden()
      const after = await snap(`${scenario}-after-guards`)
      assert.equal(after.phase, expected); assert.equal(after.policy.kind, 'BLOCK'); assert.equal(after.navigations, 0)
      assert.equal(after.writes, 1); assert.deepEqual(after.body, before.body); assert.equal(after.key, before.key)
      assert.deepEqual(after.fileNames, before.fileNames)
      if (scenario === 'unknown') await shot('tech-blue-unknown')
    })
  }
  await check('fulfilled malformed receipt is accepted-and-blocked; no raw receipt/read/handoff or repeated write', async () => {
    await reset('malformed'); await phase('ACCEPTED_READBACK')
    await expect(await recoveryAction('receipt.readOriginal', '核对原操作结果', 'read')).toBeDisabled()
    await expect(action('receipt.retryOriginal')).toHaveCount(0)
    await expect(page.getByTestId('critical-status')).toContainText('原写入已接受')
    const before = await audit(); assert.equal(before.writes, 1); assert.equal(before.reads, 0)
    await page.locator('#skin').selectOption('github-white'); await action('nav.back').click()
    await snap('malformed-accepted-no-repeat'); assert.equal((await audit()).writes, 1); assert.equal((await audit()).navigations, 0)
    assert.equal((await audit()).reads, 0); assert.equal((await audit()).policy.kind, 'BLOCK')
  })
  await check('explicit UNKNOWN retry uses identical body/key; no render/theme auto-repeat and accepted read follows once', async () => {
    await reset('unknown', 'tech-blue'); const original = await audit()
    await page.locator('#skin').selectOption('github-white'); assert.equal((await audit()).writes, 1)
    const retry = await recoveryAction('receipt.retryOriginal', '重试原操作', 'server')
    await expect(action('receipt.readOriginal')).toHaveCount(0); await retry.click(); await phase('SETTLED')
    const after = await snap('identical-unknown-recovery'); assert.equal(after.writes, 2); assert.equal(after.reads, 1)
    assert.deepEqual(after.writeInputs[1].body, after.writeInputs[0].body); assert.equal(after.writeInputs[1].requestKey, original.key)
    assert.equal(after.policy.kind, 'ALLOW'); assert.equal(after.projections, 1)
  })
  await check('accepted readback recovery uses only the mock read port; no second write or changed key', async () => {
    await reset('accepted', 'github-white'); const original = await audit()
    await phase('ACCEPTED_READBACK')
    const read = await recoveryAction('receipt.readOriginal', '核对原操作结果', 'read')
    await expect(action('receipt.retryOriginal')).toHaveCount(0); await read.click(); await phase('SETTLED')
    const after = await snap('accepted-read-only-recovery'); assert.equal(after.writes, 1); assert.equal(after.reads, 2)
    assert.equal(after.key, original.key); assert.deepEqual(after.body, original.body); assert.equal(after.projections, 1)
    await expect(page.getByTestId('critical-status')).not.toContainText('模拟只读回填失败')
    await expect(page.getByTestId('critical-status')).toContainText('原操作核对完成（模拟数据）。')
    await shot('github-white-accepted-recovered')
  })
  await check('keyless UNKNOWN exposes only original-read recovery and stays blocked when unconfirmed', async () => {
    await reset('read-only'); const before = await audit(); assert.equal(before.key, undefined)
    assert.equal(Object.hasOwn(before.body, 'requestKey'), false)
    const read = await recoveryAction('receipt.readOriginal', '核对原操作结果', 'read')
    await expect(action('receipt.retryOriginal')).toHaveCount(0); await read.click(); await expect.poll(async () => (await audit()).reads).toBe(1)
    const after = await snap('keyless-original-read'); assert.equal(after.phase, 'UNKNOWN'); assert.equal(after.writes, 1)
    assert.equal(after.policy.kind, 'BLOCK'); assert.equal(after.key, undefined); assert.deepEqual(after.body, before.body)
  })
  await check('real File object is retained across theme/view remount and passed unchanged to the only explicit write', async () => {
    await reset('file'); const original = await audit(); assert.deepEqual(original.fileNames, ['模拟.txt'])
    assert.equal(original.policy.kind, 'CONFIRM_DISCARD'); await action('nav.back').click(); await expect(modal).toBeVisible()
    await modal.locator('[data-semantic="ui.stay"]').click(); await expect(modal).toBeHidden()
    await page.locator('#skin').selectOption('tech-blue'); await resourcesGone('file-owner-detach')
    assert.deepEqual((await audit()).fileNames, original.fileNames); assert.equal((await audit()).key, original.key)
    await page.evaluate(() => window.__foundationAudit.mount()); await expect(panel).toBeVisible(); await expect.poll(async () => (await audit()).viewCount).toBe(1)
    await action('ui.save').click(); await phase('SETTLED'); const after = await snap('real-file-explicit-write')
    assert.equal(after.writes, 1); assert.equal(after.writeInputs[0].sameFiles, true); assert.deepEqual(after.writeInputs[0].fileNames, ['模拟.txt'])
  })
  await check('destructive action requires real dialog; cancel has no deletion, confirm performs exactly once', async () => {
    await reset('clean'); await page.getByTestId('select-trigger').click(); await panel.locator('[data-semantic="ui.delete"]').click()
    await expect(modal).toBeVisible(); await expectFocused(modal.locator('[data-semantic="ui.stay"]'))
    await page.keyboard.press('Escape'); await expect(modal).toBeHidden(); assert.equal((await audit()).deletes, 0)
    await expectFocused(page.getByTestId('select-trigger')); await panel.locator('[data-semantic="ui.delete"]').click(); await expect(modal).toBeVisible()
    await modal.locator('[data-semantic="ui.delete"]').click(); await expect(modal).toBeHidden(); assert.equal((await audit()).deletes, 1)
    assert.equal((await audit()).writes, 0)
  })
  await check('real reduced-motion media changes tokens/CSS without replacing focused draft or creating a writer', async () => {
    await reset('dirty', 'tech-blue'); const input = page.locator('#draft-title'); await input.focus()
    await page.evaluate(() => { window.__w1ReducedInput = document.getElementById('draft-title') })
    await page.emulateMedia({ reducedMotion: 'reduce' }); await expect(page.locator('.loopper-foundation')).toHaveAttribute('data-reduced-motion', 'true')
    const reducedProof = await page.evaluate(() => ({ sameInput: window.__w1ReducedInput === document.getElementById('draft-title'),
      activeTag: document.activeElement.tagName, activeId: document.activeElement.id, documentHasFocus: document.hasFocus() }))
    stateProofs.push({ label: 'reduced-motion-input-and-focus', snapshot: reducedProof }); assert.equal(reducedProof.sameInput, true)
    const styles = await action('ui.save').evaluate(element => ({ animation: getComputedStyle(element).animationName, transition: getComputedStyle(element).transitionDuration }))
    assert.equal(styles.animation, 'none'); assert.equal(styles.transition, '0s'); await expectFocused(input)
    assert.equal(await page.evaluate(() => window.__w1ReducedInput === document.getElementById('draft-title')), true)
    assert.equal((await audit()).writes, 0); await shot('tech-blue-reduced-motion')
    await page.emulateMedia({ reducedMotion: 'no-preference' }); await expect(page.locator('.loopper-foundation')).toHaveAttribute('data-reduced-motion', 'false')
  })
  await check('nested real Ant portals keep Tab/ShiftTab/Escape/Stay in the inner owner and never perform the outer deletion', async () => {
    await reset('nested'); await panel.locator('[data-semantic="ui.delete"]').click()
    const outer = page.getByRole('dialog', { name: '删除', exact: true })
    const inner = page.getByRole('dialog', { name: '内层模拟确认', exact: true })
    const trigger = page.locator('#inner-dialog-trigger')
    await expect(outer).toBeVisible(); await trigger.click(); await expect(inner).toBeVisible()
    await expectFocused(inner.locator('[data-semantic="ui.stay"]'))
    for (const key of ['Tab', 'Shift+Tab']) for (let index = 0; index < 4; index++) {
      await page.keyboard.press(key)
      const focus = await inner.evaluate(element => ({ insideInner: element.contains(document.activeElement), semantic: document.activeElement.dataset.semantic, documentHasFocus: document.hasFocus() }))
      stateProofs.push({ label: `nested-${key}-${index + 1}`, snapshot: focus }); assert.equal(focus.insideInner, true); assert.equal(focus.documentHasFocus, true)
    }
    await shot('spdb-nested-confirm')
    await page.keyboard.press('Escape'); await expect(inner).toBeHidden(); await expect(outer).toBeVisible(); await expectFocused(trigger)
    await trigger.click(); await expect(inner).toBeVisible(); await inner.locator('[data-semantic="ui.stay"]').click()
    await expect(inner).toBeHidden(); await expect(outer).toBeVisible(); await expectFocused(trigger)
    assert.equal((await audit()).nestedConfirmations, 0)
    await trigger.click(); await expect(inner).toBeVisible(); await inner.locator('[data-semantic="ui.discardChanges"]').click()
    await expect(inner).toBeHidden(); await expect(outer).toBeVisible(); assert.equal((await audit()).nestedConfirmations, 1)
    await outer.locator('[data-semantic="ui.stay"]').click(); await expect(outer).toBeHidden()
    const snapshot = await snap('nested-owner-boundaries'); assert.equal(snapshot.deletes, 0); assert.equal(snapshot.writes, 0)
  })
  await check('StrictMode actually replays setup; ten same-owner cycles immediately release all instance resources', async () => {
    await reset('unknown'); const original = await audit()
    const start = await page.evaluate(() => window.__w1Resources.snapshot().setups)
    for (let cycle = 0; cycle < 10; cycle++) {
      await resourcesGone(`unknown-cycle-${cycle + 1}`)
      const detached = await audit(); assert.equal(detached.phase, 'UNKNOWN'); assert.equal(detached.writes, 1)
      assert.equal(detached.key, original.key); assert.deepEqual(detached.body, original.body)
      await page.evaluate(() => window.__foundationAudit.mount()); await expect(panel).toBeVisible(); await expect.poll(async () => (await audit()).viewCount).toBe(1)
      assert.equal((await audit()).writes, 1)
      assert.equal(await page.evaluate(() => window.__w1Resources.snapshot().setups), start + (cycle + 1) * 2)
    }
  })
  await check('forced owner retirement during pending keeps the late original receipt without stale read projection', async () => {
    await reset('pending'); const original = await audit()
    const first = await page.evaluate(() => {
      window.__foundationAudit.forceExit()
      return { audit: window.__foundationAudit.snapshot(), resources: window.__w1Resources.snapshot() }
    })
    resourceProofs.push({ label: 'pending-forced-retirement', immediate: first })
    for (const key of ['listeners', 'pendingFrames', 'rawPendingFrames', 'intervals', 'observers']) assert.deepEqual(first.resources[key], [])
    assert.equal(first.audit.viewCount, 0); assert.equal(first.audit.key, original.key)
    await page.evaluate(() => window.__foundationAudit.finishPending())
    await expect.poll(async () => (await audit()).phase).toBe('ACCEPTED_READBACK')
    const late = await snap('forced-owner-late-receipt'); assert.equal(late.writes, 1); assert.equal(late.projections, 0)
    assert.equal(late.reads, 0); assert.equal(late.key, original.key); assert.deepEqual(late.body, original.body)
    assert.equal(late.accepted, true); assert.equal(late.retiredOperation, true); assert.deepEqual(late.receipt, { id: 'mock-receipt-1' })
  })
  for (const scenario of ['pending', 'accepted', 'read-only', 'dirty', 'clean']) {
    await check(`${scenario}: immediate root loss keeps receipt/draft identity and releases resources with no cleanup input`, async () => {
      await reset(scenario); const before = await audit(); await resourcesGone(`${scenario}-root-loss`)
      const after = await audit(); assert.equal(after.key, before.key); assert.deepEqual(after.body, before.body)
      assert.deepEqual(after.view, before.view); assert.equal(after.phase, before.phase); assert.equal(after.writes, before.writes)
      if (scenario === 'pending') {
        await page.evaluate(() => window.__foundationAudit.finishPending()); await expect.poll(async () => (await audit()).phase).toBe('SETTLED')
        assert.equal((await audit()).writes, 1)
      }
    })
  }
  await check('observer negative control preserves detached targets and fails the exact empty-array gate', async () => {
    const proof = await page.evaluate(() => {
      const node = document.createElement('div'); node.id = 'w1-negative-control'; document.body.appendChild(node)
      const observer = new ResizeObserver(() => {}); observer.observe(node); node.remove()
      const snapshot = window.__w1Resources.snapshot()
      observer.disconnect() // Only this test-owned observer; after the strict snapshot, never production cleanup.
      return snapshot
    })
    const row = proof.observers.find(item => item.targets.some(target => target.elementId === 'w1-negative-control'))
    assert.ok(row); assert.equal(row.targets[0].connected, false)
    assert.throws(() => assert.deepEqual(proof.observers, []))
    stateProofs.push({ label: 'detached-observer-strict-negative-control', snapshot: proof })
    assert.deepEqual(await page.evaluate(() => window.__w1Resources.snapshot().observers), [])
  })
  await check('unmount with a genuinely active Ant modal immediately removes its native focus trap and provider/controller resources', async () => {
    await reset('dirty'); await action('ui.close').click(); await expect(modal).toBeVisible()
    await expectFocused(modal.locator('[data-semantic="ui.stay"]'))
    const before = await page.evaluate(() => window.__w1Resources.snapshot())
    assert.ok(before.listeners.some(row => row.type === 'focusin' && row.target.kind === 'window'))
    assert.ok(before.listeners.some(row => row.type === 'keydown' && row.target.kind === 'window' && row.capture))
    await resourcesGone('active-ant-modal-root-loss')
    assert.equal((await audit()).view.dirty, true); assert.equal((await audit()).writes, 0)
  })
  await check('source byte freeze, no external/API/mutation requests, no browser error', async () => {
    assert.deepEqual(await sources(), beforeSources)
    assert.deepEqual(errors, [])
    assert.ok(requests.length > 0)
    for (const request of requests) { assert.equal(request.method, 'GET'); assert.equal(new URL(request.url).origin, new URL(url).origin); assert.equal(new URL(request.url).pathname.startsWith('/api/'), false) }
    for (const socket of websockets) { assert.equal(socket.hostname, '127.0.0.1'); assert.equal(socket.port, new URL(url).port); assert.equal(socket.protocol, 'ws:') }
  })
  executionComplete = true
} finally {
  const sourceAfter = await sources()
  const evidence = { schemaVersion: 1, scope: 'W1 independent desktop React/Ant mock fixture; no production routes/history/backend/SSE',
    url, browser: browser.version(), generatedAt: new Date().toISOString(), executionComplete,
    counts: { total: rows.length, passed: rows.filter(row => row.result === 'PASS').length, failed: rows.filter(row => row.result === 'FAIL').length },
    scenarios: 'Scenario reset explicitly executes mock commands only for pending/unknown/accepted/read-only; render effects never initiate them.',
    resourceBoundary: 'First synchronous unmount sample; exact w1-instance-probe, MediaQueryList modern change, window focusin/keydown registrations; fixture-owned RAF/interval stacks plus all raw native RAFs (also strict []); all native ResizeObserver active targets raw. No heap/GC, arbitrary third-party timer or React delegated-container listener claim.',
    rows, stateProofs, resourceProofs, screenshots, requests, websockets, errors, warnings, sourceBefore: beforeSources, sourceAfter }
  const bytes = `${JSON.stringify(evidence, null, 2)}\n`
  await writeFile(join(here, 'evidence.json'), bytes); await writeFile(join(raw, 'evidence.json'), bytes)
  await context.tracing.stop({ path: join(raw, 'trace.zip') }); await context.close(); await browser.close()
  process.stdout.write(`RESULT ${evidence.counts.passed} PASS / ${evidence.counts.failed} FAIL; ${screenshots.length} PNG; executionComplete=${executionComplete}\n`)
  if (evidence.counts.failed || !executionComplete) process.exitCode = 1
}
