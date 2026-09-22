import { expect, test, type Page } from '@playwright/test'
const id = '11111111-1111-4111-8111-111111111111', citationId = '22222222-2222-4222-8222-222222222222'
const sources = [{ id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', detail: '', version: 0 }, { id: 'documents', kind: 'DOCUMENTS', name: '项目文档', state: 'READY', detail: '', version: 0 }]
const citation = { id: citationId, kind: 'CODE', name: 'PaymentService.java', location: 'src/PaymentService.java · 第 12–15 行', sha256: 'a'.repeat(64), createdAt: '2026-09-17T01:00:00Z' }
const summary = { id, projectId: 'p', title: '付款流程如何工作', model: 'local/model', state: 'IDLE', sources, createdAt: '2026-09-17T01:00:00Z', updatedAt: '', version: 0 }
test('文件链接在当前页预览并保留气泡头像，失败不跳到 404', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/knowledge/conversations/*/messages?**', route => route.fulfill({ json: { items: [{ id: 'file-turn', ordinal: 1, state: 'COMPLETED', userText: '查看付款流程', thinking: '', answer: '[付款代码](src/PaymentService.java#L12-L15) [不可用文件](missing.java)', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }], nextCursor: null } }))
  await page.route('**/api/knowledge/conversations/*/file?**', route => {
    const params = new URL(route.request().url()).searchParams
    if (params.get('path') === 'missing.java') return route.fulfill({ status: 400, json: { detail: '文件不存在，请从资料来源中重新查找' } })
    expect(params.get('path')).toBe('src/PaymentService.java'); expect(params.get('startLine')).toBe('12')
    return route.fulfill({ json: { kind: 'CODE', name: 'PaymentService.java', sourceId: 'code', path: 'src/PaymentService.java', sha256: 'a'.repeat(64), text: 'void pay() {\n  check();\n  save();\n}', startLine: 12, endLine: 15, nextLine: -1, changeNotice: '当前文件预览，包含本地最新内容' } })
  })
  await page.goto(`/knowledge/${id}`)
  const avatar = page.getByRole('img', { name: '你的头像' })
  await expect(avatar).toBeVisible(); expect(await avatar.evaluate(el => (el as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
  await page.getByRole('link', { name: '付款代码' }).click()
  await expect(page.getByRole('heading', { name: '文件预览', exact: true })).toBeVisible()
  await expect(page.locator('.knowledge-evidence')).toContainText('check();')
  await expect(page).toHaveURL(`/knowledge/${id}`); expect(page.context().pages()).toHaveLength(1)
  await page.screenshot({ path: 'test-results/knowledge-file-preview.png' })
  await page.getByRole('button', { name: '关闭引用详情' }).click()
  await page.getByRole('link', { name: '不可用文件' }).click()
  await expect(page.locator('.knowledge-right [role="alert"]')).toContainText('文件不存在')
  await expect(page).toHaveURL(`/knowledge/${id}`)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '关闭引用详情' }).click()
  expect(await page.locator('.knowledge-user').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/knowledge-whale-mobile.png' })
})
for (const width of [1440, 768]) {
  test(`统一检索分页、来源覆盖与数据库原文 ${width}px`, async ({ page }) => {
    await fixture(page); await page.setViewportSize({ width, height: 900 })
    const allSources = [...sources, { id: 'database:db', kind: 'DATABASE', name: '客户数据库', state: 'READY', detail: 'app', version: 1 }]
    const requests: URL[] = []
    await page.route(`**/api/knowledge/conversations/${id}`, route => route.fulfill({ json: { ...summary, sources: allSources } }))
    await page.route('**/api/projects/p/knowledge-sources/search?**', route => {
      const url = new URL(route.request().url()); requests.push(url); const next = !url.searchParams.get('cursor')
      return route.fulfill({ json: { matches: [next
        ? { sourceId: 'code', sourceName: '项目代码', kind: 'CODE', name: 'Customer.java', path: 'Customer.java', startLine: 10, sha256: 'a'.repeat(64), snippet: 'private String customer_id;', matchType: 'FIELD' }
        : { sourceId: 'database:db', sourceName: '客户数据库', kind: 'DATABASE', name: 'customer_id', path: 'app.customer.customer_id', location: 'app.customer.customer_id', schema: 'app', table: 'customer', column: 'customer_id', sha256: 'b'.repeat(64), snippet: '客户编号 · VARCHAR', matchType: 'FIELD' }],
        nextCursor: next ? 'next' : null, incomplete: next, limitations: [], coverage: allSources.map(s => ({ sourceId: s.id, name: s.name, kind: s.kind, state: next ? 'PARTIAL' : 'COMPLETE', limited: false, examined: 50, matched: 1 })) } })
    })
    await page.route('**/api/projects/p/knowledge-sources/database%3Adb/database?**', route => {
      const params = new URL(route.request().url()).searchParams
      expect(params.get('schema')).toBe('app'); expect(params.get('table')).toBe('customer'); expect(params.get('kind')).toBe('columns')
      return route.fulfill({ json: { kind: 'DATABASE', name: '客户数据库', columns: [{ name: 'COLUMN_NAME', type: 'VARCHAR' }, { name: 'REMARKS', type: 'VARCHAR' }], rows: [['customer_id', '客户编号']], nextOffset: -1, collectedAt: '2026-09-18T00:00:00Z' } })
    })
    await page.goto(`/knowledge/${id}`); await page.getByRole('button', { name: /^来源 / }).click()
    const panel = page.locator('.knowledge-left')
    await panel.getByLabel('来源搜索').fill('customerId'); await panel.getByLabel('检索方式').selectOption('FIELD')
    await panel.getByRole('button', { name: '搜索', exact: true }).click(); await expect(panel.getByText('待继续检索', { exact: false }).first()).toBeVisible()
    await panel.getByRole('button', { name: '继续检索', exact: true }).click(); await expect(panel.locator('.knowledge-match')).toHaveCount(2)
    expect(requests).toHaveLength(2); expect(requests[1]!.searchParams.get('sourceIds')).toContain('database:db')
    expect(requests[1]!.searchParams.get('mode')).toBe('FIELD'); expect(requests[1]!.searchParams.get('cursor')).toBe('next')
    await panel.locator('.knowledge-match').last().click(); await expect(panel.locator('.knowledge-browser').getByText('客户编号', { exact: true }).first()).toBeVisible()
    await panel.locator('.knowledge-browser').scrollIntoViewIfNeeded()
    await expect(panel.getByRole('button', { name: '下一页结构', exact: true })).toHaveCount(0)
    expect(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: `test-results/knowledge-search-${width}.png`, fullPage: true })
    await panel.getByLabel('来源搜索').fill('另一字段'); await expect(panel.locator('.knowledge-search-results')).toHaveCount(0)
  })
}
async function fixture(page: Page) {
  let state = 'IDLE'; let sent = false; let archivedAt: string | null = null; let archiveVersion = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/projects/summaries') return route.fulfill({ json: [{ id: 'p', name: '客户服务项目', rootPath: '/project', status: 'READY', taskCount: 0, openDesignerSessionCount: 0 }] })
    if (path === '/api/settings/models') return route.fulfill({ json: [{ id: 'local/model', provider: 'local', model: 'model', label: '项目问答模型' }] })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: { mode: 'managed', provider: 'local', model: 'model' }, limits: {}, retryWait: {}, publication: {} } })
    if (path.endsWith('/knowledge-sources')) return route.fulfill({ json: { items: sources, nextCursor: null } })
    if (path.endsWith('/directory')) return route.fulfill({ json: { items: [{ path: 'src/PaymentService.java', name: '很长的项目业务付款服务名称用于验证换行和布局PaymentService.java', directory: false, bytes: 100 }], nextCursor: null, incomplete: false, detail: '' } })
    if (path.endsWith('/content')) return route.fulfill({ status: 400, json: { detail: '文件内容已变化，请重新读取' } })
    if (path.endsWith(`/citations/${citationId}`)) return route.fulfill({ json: { citation, body: { kind: 'CODE', name: citation.name, location: citation.location, sha256: citation.sha256, startLine: 12, endLine: 15, text: 'public void pay() {\n  approval.requireApproved();\n  repository.save(payment.withDescription("' + '超长代码字符串'.repeat(50) + '"));\n}' } } })
    if (path.endsWith('/archive')) { archivedAt = route.request().postDataJSON().archived ? new Date().toISOString() : null; archiveVersion++; return route.fulfill({ json: { ...summary, options: { archivedAt, version: archiveVersion } } }) }
    if (path.endsWith('/stop')) { state = 'IDLE'; return route.fulfill({ json: { ...summary, state } }) }
    if (path.endsWith('/messages')) {
      if (route.request().method() === 'POST') { sent = true; state = 'RUNNING'; return route.fulfill({ json: {} }) }
      return route.fulfill({ json: { items: [{ id: 'turn1', ordinal: 1, state: sent && state === 'RUNNING' ? 'RUNNING' : sent ? 'STOPPED' : 'COMPLETED', userText: '付款流程如何工作？', thinking: '先定位付款入口，再核对审批条件。\n\n根据实际读取的代码与文档确认结论。', answer: `付款前必须完成审批，然后保存付款记录。[1](knowledge:${citationId}#L13-L14)\n\n### 实现依据\n\n${'这里是从项目代码读取的流程说明。'.repeat(80)}`, detail: sent && state === 'IDLE' ? '已停止生成，以上为未完成回答' : '', inputTokens: 1200, outputTokens: 450, createdAt: '', citations: [citation], calls: [{ id: 'call', tool: 'read_knowledge_source', state: 'SUCCEEDED', detail: '' }] }], nextCursor: null } })
    }
    if (path === `/api/knowledge/conversations/${id}`) return route.fulfill({ json: { ...summary, state } })
    if (path === '/api/knowledge/conversations') {
      if (route.request().method() === 'POST' && route.request().postDataJSON().model !== 'local/model')
        return route.fulfill({ status: 400, json: { detail: '所选模型不可用，请刷新模型列表' } })
      return route.fulfill({ json: route.request().method() === 'POST' ? summary : { items: url.searchParams.get('archive') === 'archived' && !archivedAt || url.searchParams.get('archive') === 'active' && archivedAt ? [] : [{ ...summary, options: { archivedAt, version: archiveVersion } }], nextCursor: null } })
    }
    return route.fulfill({ json: [] })
  })
}
for (const width of [1440, 768]) {
  test(`无思考内容时的动态等待与回复切换 ${width}px`, async ({ page }) => {
    await fixture(page); await page.setViewportSize({ width, height: 900 })
    let answer = '', thinking = '', state = 'RUNNING'
    await page.route(`**/api/knowledge/conversations/${id}`, route => route.fulfill({ json: { ...summary, state: state === 'RUNNING' ? 'RUNNING' : 'IDLE' } }))
    await page.route(`**/conversations/${id}/messages?**`, route => route.fulfill({ json: { items: [{ id: 'empty', ordinal: 1, state, userText: '这个项目的核心流程是什么？', answer, thinking, detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }], nextCursor: null } }))
    await page.route(`**/conversations/${id}/stop`, route => { state = 'STOPPED'; return route.fulfill({ json: summary }) })
    await page.goto(`/knowledge/${id}`)
    const waiting = page.locator('.knowledge-waiting')
    await expect(waiting).toHaveText('正在思考')
    await expect(page.locator('section[aria-label="思考"]')).toHaveCount(0)
    const dots = waiting.locator('i')
    await expect(dots).toHaveCount(3)
    expect(await dots.first().evaluate(el => el.getAnimations().some(a => a.playState === 'running'))).toBe(true)
    const before = await dots.first().evaluate(el => getComputedStyle(el).opacity)
    await page.waitForTimeout(220)
    expect(await dots.first().evaluate(el => getComputedStyle(el).opacity)).not.toBe(before)
    expect(await page.locator('body').evaluate(el => el.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/knowledge-waiting-${width}.png`, fullPage: true })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    expect(await dots.first().evaluate(el => el.getAnimations().length)).toBe(0)
    await expect(waiting).toBeVisible()
    thinking = '先检查实际项目入口。'; await page.reload()
    await expect(waiting).toHaveCount(0); await expect(page.locator('section[aria-label="思考"]')).toBeVisible()
    thinking = ''; answer = '项目从接口接收请求，再进入业务处理。'; await page.reload()
    await expect(waiting).toHaveCount(0); await expect(page.getByText(answer, { exact: true })).toBeVisible()
    answer = ''; await page.reload(); await expect(waiting).toBeVisible()
    await page.getByRole('button', { name: '停止生成', exact: true }).click()
    await expect(waiting).toHaveCount(0); await expect(page.locator('.knowledge-answer-label')).toContainText('已停止')
  })
}
for (const width of [1920, 1440, 1280, 768]) {
  test(`知识问答来源与引用面板 ${width}px`, async ({ page }) => {
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
    await fixture(page); await page.setViewportSize({ width, height: 1000 }); await page.goto(`/knowledge/${id}`)
    await expect(page.getByText('付款流程如何工作？', { exact: true })).toBeVisible()
    const left = page.locator('.knowledge-left'), right = page.locator('.knowledge-right')
    await expect(left).not.toBeVisible(); await expect(right).not.toBeVisible()
    await page.screenshot({ path: `test-results/knowledge-default-${width}.png`, fullPage: true })
    const trigger = page.getByRole('button', { name: /^来源 / }); await trigger.click(); await expect(left).toBeVisible()
    await left.getByRole('button', { name: /项目代码/ }).click()
    await expect(left.getByRole('button', { name: /很长的项目业务/ })).toBeVisible()
    await left.getByRole('button', { name: /很长的项目业务/ }).click(); await expect(left.getByRole('alert')).toContainText('文件内容已变化')
    if (width < 1100) { await page.keyboard.press('Escape'); await expect(left).not.toBeVisible(); await expect(trigger).toBeFocused() }
    const chatBeforeOpen = await page.locator('.knowledge-chat').boundingBox()
    const scrollBeforeOpen = await page.locator('.knowledge-timeline').evaluate(el => el.scrollTop)
    await page.getByRole('link', { name: '1', exact: true }).click()
    await expect(right).toBeVisible(); await expect(right.getByText('PaymentService.java', { exact: true })).toBeVisible()
    if (width >= 1100) await expect(left).toBeVisible(); else await expect(left).not.toBeVisible()
    if (width < 1100) { await expect(right).toHaveAttribute('aria-modal', 'true'); await page.keyboard.press('Shift+Tab'); expect(await right.evaluate(el => el.contains(document.activeElement))).toBe(true) }
    expect(await page.locator('.knowledge-chat').boundingBox()).toEqual(chatBeforeOpen)
    expect(await page.locator('.knowledge-timeline').evaluate(el => el.scrollTop)).toBe(scrollBeforeOpen)
    await expect(right.locator('.cm-evidence-highlight')).toHaveCount(2)
    await expect(right.locator('.cm-lineWrapping')).toBeVisible()
    expect(await right.locator('.cm-scroller').evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
    if (width >= 1100) {
      const grip = page.getByRole('separator', { name: '调整引用面板宽度' })
      const before = (await right.boundingBox())!.width, chatBefore = (await page.locator('.knowledge-chat').boundingBox())!.width
      const point = (await grip.boundingBox())!; await page.mouse.move(point.x + point.width / 2, point.y + 180); await page.mouse.down(); await page.mouse.move(point.x - 90, point.y + 180, { steps: 8 }); await page.mouse.up()
      const after = (await right.boundingBox())!.width
      expect(after).toBeGreaterThan(before + 60); expect((await page.locator('.knowledge-chat').boundingBox())!.width).toBeCloseTo(chatBefore, 0)
      await grip.focus(); await page.keyboard.press('End')
      expect((await page.locator('.knowledge-chat').boundingBox())!.width).toBeGreaterThanOrEqual(359)
      expect(await page.locator('.knowledge-timeline').evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
      await page.keyboard.press('ArrowRight'); await grip.dblclick()
    }
    await page.screenshot({ path: `test-results/knowledge-citation-${width}.png`, fullPage: true })
    await right.getByRole('button', { name: '关闭引用详情' }).click(); await expect(right).not.toBeVisible()
    if (width >= 1100) await left.getByRole('button', { name: '关闭来源' }).click()
    expect(await page.locator('body').evaluate(el => el.scrollWidth <= window.innerWidth)).toBe(true)
    await page.getByRole('textbox', { name: '向项目提问' }).fill('继续解释审批流程')
    await page.getByRole('button', { name: '发送', exact: false }).click()
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible()
    const thinking = page.locator('section[aria-label="思考"]'); await expect(thinking.getByRole('button')).toHaveAttribute('aria-expanded', 'false'); await thinking.getByRole('button').click()
    await expect(thinking.locator('.knowledge-thinking-content')).toBeVisible()
    await page.screenshot({ path: `test-results/knowledge-thinking-${width}.png`, fullPage: true })
    await page.getByRole('button', { name: '停止生成', exact: true }).click(); await expect(page.getByText('已停止生成，以上为未完成回答')).toBeVisible()
    await expect(page.locator('section[aria-label="工具调用"] .knowledge-spinner')).not.toBeVisible()
    expect(errors).toEqual([])
  })
}
test('新对话默认模型、来源与深层历史路由', async ({ page }) => {
  await fixture(page); await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: '让项目知识，成为答案' })).toBeVisible()
  await page.getByRole('button', { name: '更换问答模型' }).click()
  await expect(page.getByRole('combobox', { name: '问答模型' })).toHaveValue('local/model')
  await page.getByRole('button', { name: '关闭模型选择' }).click()
  await page.getByRole('link', { name: '历史对话', exact: true }).click()
  await page.getByRole('button', { name: /付款流程如何工作.*项目/ }).click()
  await expect(page).toHaveURL(`/knowledge/${id}`); await expect(page.getByRole('combobox', { name: '选择项目' })).toBeDisabled()
  await page.reload(); await expect(page.getByText('付款流程如何工作？', { exact: true })).toBeVisible()
})
test('继承提供方与模型名并发送完整模型标识', async ({ page }) => {
  await fixture(page); await page.goto('/knowledge')
  await page.getByRole('button', { name: '更换问答模型' }).click()
  await expect(page.getByRole('combobox', { name: '问答模型' })).toHaveValue('local/model')
  await page.getByRole('button', { name: '关闭模型选择' }).click()
  await page.getByRole('textbox', { name: '向项目提问' }).fill('当前项目有几个模块？')
  const creation = page.waitForRequest(request => request.url().endsWith('/api/knowledge/conversations') && request.method() === 'POST')
  await page.getByRole('button', { name: '发送', exact: false }).click()
  expect((await creation).postDataJSON().model).toBe('local/model')
  await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible()
})

test('模型目录挂起不影响进入、输入或默认发送；选择器独立显示等待', async ({ page }) => {
  await fixture(page)
  let release!: () => void; const wait = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/settings/models', async route => { await wait; await route.fulfill({ json: [] }) })
  try {
    await page.goto('/knowledge', { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: '让项目知识，成为答案' })).toBeVisible()
    await page.getByRole('textbox', { name: '向项目提问' }).fill('无需等模型目录')
    await expect(page.getByRole('button', { name: '发送', exact: false })).toBeEnabled()
    await page.getByRole('button', { name: '更换问答模型' }).click()
    await expect(page.getByRole('dialog', { name: '选择问答模型' })).toContainText('正在读取其他模型')
    await expect(page.getByRole('combobox', { name: '问答模型' })).toHaveValue('local/model')
    await page.getByRole('button', { name: '关闭模型选择' }).click()
    await page.getByRole('button', { name: '发送', exact: false }).click()
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible()
  } finally { release() }
})

test('引用栏拖动宽度在重新打开和刷新后保持', async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto(`/knowledge/${id}`)
  const right = page.locator('.knowledge-right')
  await page.getByRole('link', { name: '1', exact: true }).click()
  const grip = page.getByRole('separator', { name: '调整引用面板宽度' })
  await grip.focus(); await page.keyboard.press('ArrowLeft'); await page.keyboard.press('ArrowLeft')
  const resized = (await right.boundingBox())!.width
  await page.getByRole('button', { name: '关闭引用详情' }).click(); await page.getByRole('link', { name: '1', exact: true }).click()
  expect((await right.boundingBox())!.width).toBeCloseTo(resized, 0)
  await page.reload(); await page.getByRole('link', { name: '1', exact: true }).click()
  expect((await right.boundingBox())!.width).toBeCloseTo(resized, 0)
})


test('侧栏返回正在回答的知识对话、恢复草稿并读取离开期间的结果', async ({ page }) => {
  await fixture(page)
  let completed = false, subscriptions = 0
  const writes: string[] = [], errors: string[] = []
  page.on('request', request => { if (request.url().includes('/api/') && request.method() === 'POST') writes.push(request.url()) })
  page.on('pageerror', error => errors.push(error.message))
  await page.route(`**/api/knowledge/conversations/${id}`, route => route.fulfill({ json: { ...summary, state: completed ? 'IDLE' : 'RUNNING' } }))
  await page.route(`**/conversations/${id}/messages?**`, route => route.fulfill({ json: { items: [{ id: 'returning', ordinal: 1, state: completed ? 'COMPLETED' : 'RUNNING', userText: '检查项目流程', answer: completed ? '离开期间已完成的回答' : '', thinking: '', detail: '', inputTokens: null, outputTokens: null, createdAt: '', citations: [], calls: [] }], nextCursor: null } }))
  await page.route(`**/conversations/${id}/events`, route => { subscriptions++; return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' }) })
  await page.goto(`/knowledge/${id}`)
  await expect(page.locator('.knowledge-waiting')).toHaveText('正在思考')
  await page.getByRole('textbox', { name: '向项目提问' }).fill('返回后继续问的草稿')
  const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
  await navigation.getByRole('link', { name: '主页', exact: true }).click(); await expect(page).toHaveURL('/')
  const beforeReturn = subscriptions; completed = true
  await navigation.getByRole('link', { name: '知识库', exact: true }).click()
  await expect(page).toHaveURL(`/knowledge/${id}`)
  await expect(page.getByText('离开期间已完成的回答', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '向项目提问' })).toHaveValue('返回后继续问的草稿')
  await expect(page.locator('.knowledge-waiting')).toHaveCount(0)
  await expect.poll(() => subscriptions).toBeGreaterThan(beforeReturn)
  await expect(navigation.getByRole('link', { name: '知识库', exact: true })).toHaveClass(/router-link-active/)
  expect(writes).toEqual([]); expect(errors).toEqual([])
})

test('侧栏记住新创建的对话，其他页面刷新后仍可返回，主动新建回到首页', async ({ page }) => {
  await fixture(page); await page.goto('/knowledge')
  await page.getByRole('textbox', { name: '向项目提问' }).fill('付款流程如何工作？')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page).toHaveURL(`/knowledge/${id}`)
  const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
  await navigation.getByRole('link', { name: '主页', exact: true }).click(); await expect(page).toHaveURL('/')
  await page.reload(); await expect(page).toHaveURL('/')
  await navigation.getByRole('link', { name: '知识库', exact: true }).click()
  await expect(page).toHaveURL(`/knowledge/${id}`)
  await expect(page.getByRole('textbox', { name: '向项目提问' })).toBeEnabled()
  await page.getByRole('button', { name: '新建对话', exact: true }).click()
  await expect(page).toHaveURL('/knowledge')
  await navigation.getByRole('link', { name: '主页', exact: true }).click(); await expect(page).toHaveURL('/')
  await navigation.getByRole('link', { name: '知识库', exact: true }).click()
  await expect(page).toHaveURL('/knowledge')
  await expect(page.getByRole('heading', { name: '让项目知识，成为答案' })).toBeVisible()
})

test('侧栏返回历史对话页并保留筛选，浏览器后退仍进入原对话', async ({ page }) => {
  await fixture(page); await page.goto(`/knowledge/${id}`)
  await page.getByRole('link', { name: '历史对话', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('审批')
  await expect(page).toHaveURL(/query=%E5%AE%A1%E6%89%B9/)
  const historyUrl = page.url(), navigation = page.getByRole('navigation', { name: '主导航', exact: true })
  await navigation.getByRole('link', { name: '主页', exact: true }).click(); await expect(page).toHaveURL('/')
  await navigation.getByRole('link', { name: '知识库', exact: true }).click()
  await expect(page).toHaveURL(historyUrl)
  await expect(page.getByRole('textbox', { name: '搜索历史对话' })).toHaveValue('审批')
  await expect(page.getByRole('combobox', { name: '按项目筛选' })).toHaveValue('p')
  await page.goBack(); await expect(page).toHaveURL('/')
  await page.goBack(); await expect(page).toHaveURL(historyUrl)
  await page.goBack(); await expect(page).toHaveURL(`/knowledge/${id}`)
  await expect(navigation.getByRole('link', { name: '知识库', exact: true })).toHaveAttribute('href', `/knowledge/${id}`)
})

test('历史独立筛选、归档、恢复和返回草稿', async ({ page }) => {
  await fixture(page); await page.goto(`/knowledge/${id}`)
  await page.getByRole('textbox', { name: '向项目提问' }).fill('仍未发送的草稿')
  await page.getByRole('link', { name: '历史对话', exact: true }).click()
  await expect(page.getByRole('heading', { name: '历史对话', exact: true })).toBeVisible()
  const archive = page.getByRole('button', { name: '归档对话 付款流程如何工作' })
  await archive.click(); await expect(archive).not.toBeVisible()
  await page.getByRole('combobox', { name: '归档筛选' }).selectOption('archived')
  await page.getByRole('button', { name: '恢复对话 付款流程如何工作' }).click()
  await page.getByRole('combobox', { name: '归档筛选' }).selectOption('active')
  const request = page.waitForRequest(r => r.url().includes('/api/knowledge/conversations?') && new URL(r.url()).searchParams.get('query') === '审批')
  await page.getByRole('textbox', { name: '搜索历史对话' }).fill('审批'); await request
  await page.getByRole('button', { name: /付款流程如何工作.*项目/ }).click()
  await expect(page.getByRole('textbox', { name: '向项目提问' })).toHaveValue('仍未发送的草稿')
})

test('文档阅读视图和数据库结果的引用范围高亮', async ({ page }) => {
  await fixture(page); await page.goto(`/knowledge/${id}`)
  await page.route(`**/citations/${citationId}`, route => route.fulfill({ json: { citation, body: { kind: 'DOCUMENT', name: '设计文档', startLine: 12, endLine: 15, text: '# 付款设计\n\n审批后才能付款。\n保存记录。' } } }))
  await page.getByRole('link', { name: '1', exact: true }).click()
  await expect(page.locator('.knowledge-right .cm-evidence-highlight')).toHaveCount(2)
  await page.getByRole('button', { name: '阅读视图', exact: true }).click()
  await expect(page.locator('.knowledge-right .evidence-highlight')).toContainText('审批后才能付款')
  await page.getByRole('button', { name: '关闭引用详情' }).click()
  await page.route(`**/citations/${citationId}`, route => route.fulfill({ json: { citation: { ...citation, kind: 'DATABASE' }, body: { kind: 'DATABASE', columns: ['名称', '状态'], rows: [['订单1','已审批'],['订单2','待审批']], sql: 'SELECT name, state FROM orders' } } }))
  // Bare evidence entry selects the saved result's complete bounded range.
  await page.getByRole('button', { name: /引用详情/ }).click()
  await expect(page.locator('.knowledge-right tr.is-highlighted')).toHaveCount(2)
})


test('助手澄清问题的草稿可刷新恢复且只提交一次', async ({ page }) => {
  await fixture(page)
  let answers: string[][] = []; let submits = 0
  await page.route(`**/conversations/${id}/messages?**`, route => route.fulfill({ json: { items: [{ id: 'turn-q', ordinal: 1, state: 'RUNNING', userText: '张三昨天做了什么？', thinking: '', answer: '', detail: '', inputTokens: null, outputTokens: null, citations: [], calls: [], questions: [{ id: 'q', state: answers.length ? 'ANSWERED' : 'PENDING', version: answers.length ? 1 : 0, answers, questions: [{ question: '你指哪位作者？', header: '作者', multiple: false, custom: true, options: [{ label: '张三 A', description: '研发组' }, { label: '张三 B', description: '测试组' }] }] }] }], nextCursor: null } }))
  await page.route(`**/conversations/${id}/questions/q/reply`, route => { submits++; answers = route.request().postDataJSON().answers; return route.fulfill({ json: { state: 'PREPARED' } }) })
  await page.goto(`/knowledge/${id}`)
  await page.getByRole('radio', { name: /张三 A/ }).check()
  await page.reload(); await expect(page.getByRole('radio', { name: /张三 A/ })).toBeChecked()
  await page.getByRole('button', { name: '提交回答并继续' }).click()
  await expect(page.locator('.knowledge-question')).toContainText('已回答')
  expect(answers).toEqual([['张三 A']]); expect(submits).toBe(1)
  await page.reload(); await expect(page.locator('.knowledge-question')).toContainText('已回答'); expect(submits).toBe(1)
})

test('从正文底部打开引用保留非零滚动位置，关闭后继续阅读', async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 1280, height: 720 }); await page.goto(`/knowledge/${id}`)
  const timeline = page.locator('.knowledge-timeline'), answer = page.locator('.knowledge-answer')
  await expect(page.getByText('付款流程如何工作？', { exact: true })).toBeVisible()
  await timeline.evaluate(el => { el.scrollTop = el.scrollHeight })
  const scroll = await timeline.evaluate(el => el.scrollTop), bounds = await answer.boundingBox()
  expect(scroll).toBeGreaterThan(0)
  await page.getByRole('button', { name: /引用详情/ }).click()
  await expect(page.locator('.knowledge-right')).toBeVisible()
  expect(await timeline.evaluate(el => el.scrollTop)).toBe(scroll); expect(await answer.boundingBox()).toEqual(bounds)
  await page.getByRole('button', { name: '关闭引用详情' }).click()
  expect(await timeline.evaluate(el => el.scrollTop)).toBe(scroll); expect(await answer.boundingBox()).toEqual(bounds)
})
