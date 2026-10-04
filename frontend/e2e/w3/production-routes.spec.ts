import { expect, test, type Page } from '@playwright/test'
import { SKIN_STORAGE_KEY } from '../../src/themes/registry'
import { semanticName } from './semantics'
import { observeW2Resources, assertW2Disposed } from '../w2/resources'
import { w3ProductFixture, documentRun, sourceRun, conversation, requirement, historicalRule, historicalTemplate, latestExport, workspaceExport } from './productFixture'
import { immediateW3Exit, record, stableShot } from './evidence'
import { readFile } from 'node:fs/promises'

const states = [
  { kind: 'catalog', path: '/template-tasks', title: '任务模板' },
  { kind: 'document', path: `/template-tasks/document-runs/${documentRun.id}`, title: documentRun.title },
  { kind: 'source', path: `/template-tasks/source-runs/${sourceRun.id}`, title: sourceRun.title },
  { kind: 'knowledge-new', path: '/knowledge', title: '知识库' },
  { kind: 'knowledge-existing', path: `/knowledge/${conversation.id}`, title: '知识库' },
] as const
async function loaded(page: Page, kind: typeof states[number]['kind'], title: string) {
  await expect(page.locator('[data-react-page]')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  if (kind === 'catalog') await expect(page.getByRole('button', { name: '快照代码评审', exact: true })).toBeVisible()
  if (kind === 'knowledge-new') await expect(page.getByRole('heading', { name: '让项目知识，成为答案' })).toBeVisible()
  if (kind === 'knowledge-existing') await expect(page.locator('.react-mermaid-svg > svg')).toBeVisible()
  if (kind === 'document') await expect(page.getByRole('button', { name: semanticName('selection.select', requirement.title) })).toBeVisible()
  if (kind === 'source') await expect(page.getByText('Finance.java', { exact: true })).toBeVisible()
}
async function heavyContent(page: Page, kind: typeof states[number]['kind']) {
  if (kind === 'knowledge-existing') {
    await page.locator('[data-semantic="knowledge.openCitation"]').click()
    await expect(page.locator('[data-code-renderer="react-lezer"]')).toBeVisible()
    await expect(page.getByRole('textbox', { name: '引用原文片段' })).toHaveAttribute('aria-readonly', 'true')
    await expect(page.locator('.w3-code-text')).toHaveText(['class Finance {', '  int amount() { return 105; }', '}'])
    await expect(page.locator('.w3-code-line.evidence-highlight')).toHaveCount(3)
    await expect(page.locator('.w3-code-line.evidence-highlight').nth(1)).toHaveAttribute('data-line', '2')
  }
  if (kind === 'document') {
    const selector = page.getByRole('button', { name: semanticName('selection.select', requirement.title) }); await selector.click()
    await expect(page.getByText('每笔核算保留原文和执行依据。', { exact: true })).toBeVisible()
    await page.getByText('代码证据与已检查范围', { exact: true }).click(); await expect(page.locator('[data-code-renderer="react-lezer"]')).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'Finance.java', exact: true })).toHaveAttribute('aria-readonly', 'true')
    await expect(page.locator('.w3-code-text')).toHaveText(['class Finance {', '  int amount() { return 105; }', '}'])
    await page.keyboard.press('Escape'); await expect(selector).toBeFocused()
    await page.getByRole('button', { name: semanticName('selection.select', 'summary.md') }).click()
    await expect(page.locator('.react-mermaid-svg > svg')).toBeVisible()
  }
  if (kind === 'source') {
    await page.getByRole('button', { name: semanticName('ui.expand', '处理依据') }).click()
    await expect(page.getByText('文档章节：核算流程')).toBeVisible()
    await page.getByRole('button', { name: semanticName('selection.select', 'overview.md') }).click()
    await expect(page.locator('.react-mermaid-svg > svg')).toBeVisible()
  }
}

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  for (const entry of states) test(`${skin} production ${entry.kind} direct/reload/back and three strict SPA exits`, async ({ page }) => {
    const fixture = await w3ProductFixture(page); await observeW2Resources(page)
    await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.setViewportSize({ width: skin === 'github-white' ? 1280 : 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto(entry.path); await loaded(page, entry.kind, entry.title); await page.reload(); await loaded(page, entry.kind, entry.title)
    await stableShot(page, `${skin}-${entry.kind}-default.png`)
    const rounds = []
    for (let cycle = 0; cycle < 3; cycle++) {
      if (cycle) { await page.goBack(); await loaded(page, entry.kind, entry.title) }
      await heavyContent(page, entry.kind)
      if (!cycle && ['knowledge-existing', 'document', 'source'].includes(entry.kind)) await stableShot(page, `${skin}-${entry.kind}-selected.png`)
      await page.evaluate(() => window.__w2Resources.begin())
      const before = await page.evaluate(() => window.__w2Resources.snapshot()); expect(before.rootConnected).toBe(true); expect(before.listeners.some(row => row.type === 'beforeunload')).toBe(true); expect(before.applicationMediaListeners.some(row => row.type === 'change')).toBe(true); expect(before.sentinel.active).toBe(true)
      const streamsBefore = await page.evaluate(() => (window as unknown as { __w2Streams: { opened: string[]; closed: string[] } }).__w2Streams)
      const immediate = await immediateW3Exit(page), streamsAfter = await page.evaluate(() => (window as unknown as { __w2Streams: { opened: string[]; closed: string[] } }).__w2Streams)
      rounds.push({ cycle, before, immediate, streamsBefore, streamsAfter }); await record(`${skin}-${entry.kind}-threecycles.json`, rounds); assertW2Disposed(before, immediate)
      const ownerUrl = entry.kind === 'document' || entry.kind === 'source' ? `/api${entry.path}/events` : entry.kind === 'knowledge-existing' ? `/api/knowledge/conversations/${conversation.id}/events` : ''
      if (ownerUrl) { expect(streamsBefore.opened.filter(url => url === ownerUrl).length - streamsBefore.closed.filter(url => url === ownerUrl).length).toBe(entry.kind === 'knowledge-existing' ? 1 : 0); expect(streamsAfter.opened.filter(url => url === ownerUrl)).toHaveLength(streamsAfter.closed.filter(url => url === ownerUrl).length) }
      expect(streamsAfter.closed.filter(url => url.includes('story-accounting'))).toEqual(streamsBefore.closed.filter(url => url.includes('story-accounting')))
      await expect(page).toHaveURL(/\/inbox$/)
    }
    await page.goBack(); await loaded(page, entry.kind, entry.title); await page.goForward(); await expect(page).toHaveURL(/\/inbox$/)
    expect(fixture.requests.filter(request => request.method !== 'GET')).toEqual([]); expect(fixture.base.requests.filter(request => request.method !== 'GET')).toEqual([]); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([])
  })

  test(`${skin} explicit historical consumer reads all nine GETs and preserves both original export bytes`, async ({ page }) => {
    const fixture = await w3ProductFixture(page); await observeW2Resources(page); await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin }); await page.setViewportSize({ width: 1440, height: 1000 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await page.goto('/template-tasks'); await loaded(page, 'catalog', '任务模板')
    expect(fixture.requests.filter(request => request.path.startsWith('/api/automations/'))).toEqual([])
    await page.locator('[data-semantic="automation.readArchive"]').click(); const history = page.getByRole('complementary', { name: '历史模板与自动化记录' }); await expect(history.getByText('模拟 Git 读取中断，请核对记录。')).toBeVisible(); await expect(history.locator('[data-code-renderer="react-lezer"]')).toBeVisible()
    await history.getByRole('tab', { name: '历史模板', exact: true }).click(); await history.getByRole('button', { name: semanticName('ui.open', historicalTemplate.name) }).click(); await expect(history.getByText('不可变', { exact: false })).toBeVisible()
    await history.locator('section[aria-label="冻结模板版本"] summary').click()
    await expect(history.getByRole('textbox', { name: '历史冻结合同' })).toBeVisible(); await expect(history.getByRole('textbox', { name: '历史冻结合同' })).toContainText('原始冻结合同'); await expect(history.getByRole('textbox', { name: '历史冻结合同' })).toHaveAttribute('aria-readonly', 'true')
    const latest = page.waitForEvent('download'); await history.locator('[data-semantic="automation.exportLatest"]').click(); const latestFile = await latest; expect(await readFile((await latestFile.path())!, 'utf8')).toBe(latestExport)
    await history.getByRole('tab', { name: '旧规则与检测记录' }).click(); await history.getByRole('button', { name: semanticName('ui.open', historicalRule.name) }).click(); await expect(history.getByRole('button', { name: semanticName('ui.open', 'w3-old-run') })).toBeVisible()
    await history.getByRole('tab', { name: '运行记录', exact: true }).click(); await history.getByRole('button', { name: semanticName('ui.open', 'w3-old-run') }).click(); await expect(history.getByRole('region', { name: '历史运行详情' })).toContainText('frozen')
    await history.getByRole('tab', { name: '导出说明' }).click(); const all = page.waitForEvent('download'); await history.locator('[data-semantic="automation.exportWorkspace"]').click(); const workspaceFile = await all; expect(await readFile((await workspaceFile.path())!, 'utf8')).toBe(workspaceExport)
    expect([...new Set(fixture.requests.filter(request => request.path.startsWith('/api/automations/')).map(request => request.path))].sort()).toEqual(['/api/automations/workspace', '/api/automations/templates', `/api/automations/templates/${historicalTemplate.id}`, `/api/automations/templates/${historicalTemplate.id}/versions`, `/api/automations/templates/${historicalTemplate.id}/export`, '/api/automations/templates/export', '/api/automations/rules', `/api/automations/rules/${historicalRule.id}/runs`, '/api/automations/runs'].sort())
    await stableShot(page, `${skin}-history-archive-selected.png`); await page.evaluate(() => window.__w2Resources.begin()); const before = await page.evaluate(() => window.__w2Resources.snapshot()); const immediate = await immediateW3Exit(page); await record(`${skin}-history-archive-exit.json`, { before, immediate }); assertW2Disposed(before, immediate)
    expect(fixture.requests.every(request => request.method === 'GET')).toBe(true); expect(fixture.errors).toEqual([]); expect(fixture.unexpected).toEqual([]); expect(fixture.base.unexpected).toEqual([])
  })
}
