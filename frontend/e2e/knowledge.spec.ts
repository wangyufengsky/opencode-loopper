import { expect, test, type Page } from '@playwright/test'
const id = '11111111-1111-4111-8111-111111111111', citationId = '22222222-2222-4222-8222-222222222222'
const sources = [{ id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', detail: '', version: 0 }, { id: 'documents', kind: 'DOCUMENTS', name: '项目文档', state: 'READY', detail: '', version: 0 }]
const citation = { id: citationId, kind: 'CODE', name: 'PaymentService.java', location: 'src/PaymentService.java · 第 12–15 行', sha256: 'a'.repeat(64), createdAt: '2026-09-17T01:00:00Z' }
const summary = { id, projectId: 'p', title: '付款流程如何工作', model: 'local/model', state: 'IDLE', sources, createdAt: '2026-09-17T01:00:00Z', updatedAt: '', version: 0 }
async function fixture(page: Page) {
  let state = 'IDLE'; let sent = false
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/projects/summaries') return route.fulfill({ json: [{ id: 'p', name: '客户服务项目', rootPath: '/project', status: 'READY', taskCount: 0, openDesignerSessionCount: 0 }] })
    if (path === '/api/settings/models') return route.fulfill({ json: [{ id: 'local/model', provider: 'local', model: 'model', label: '项目问答模型' }] })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: { mode: 'managed', model: 'local/model' }, limits: {}, retryWait: {}, publication: {} } })
    if (path.endsWith('/knowledge-sources')) return route.fulfill({ json: { items: sources, nextCursor: null } })
    if (path.endsWith('/directory')) return route.fulfill({ json: { items: [{ path: 'src/PaymentService.java', name: '很长的项目业务付款服务名称用于验证换行和布局PaymentService.java', directory: false, bytes: 100 }], nextCursor: null, incomplete: false, detail: '' } })
    if (path.endsWith('/content')) return route.fulfill({ status: 400, json: { detail: '文件内容已变化，请重新读取' } })
    if (path.endsWith(`/citations/${citationId}`)) return route.fulfill({ json: { citation, body: { kind: 'CODE', name: citation.name, location: citation.location, sha256: citation.sha256, startLine: 12, endLine: 15, text: 'public void pay() {\n  approval.requireApproved();\n  repository.save(payment);\n}' } } })
    if (path.endsWith('/stop')) { state = 'IDLE'; return route.fulfill({ json: { ...summary, state } }) }
    if (path.endsWith('/messages')) {
      if (route.request().method() === 'POST') { sent = true; state = 'RUNNING'; return route.fulfill({ json: {} }) }
      return route.fulfill({ json: { items: [{ id: 'turn1', ordinal: 1, state: sent && state === 'RUNNING' ? 'RUNNING' : sent ? 'STOPPED' : 'COMPLETED', userText: '付款流程如何工作？', answer: `付款前必须完成审批，然后保存付款记录。[1](knowledge:${citationId})\n\n### 实现依据\n\n${'这里是从项目代码读取的流程说明。'.repeat(80)}`, detail: sent && state === 'IDLE' ? '已停止生成，以上为未完成回答' : '', inputTokens: 1200, outputTokens: 450, createdAt: '', citations: [citation], calls: [{ id: 'call', tool: 'read_knowledge_source', state: 'SUCCEEDED', detail: '' }] }], nextCursor: null } })
    }
    if (path === `/api/knowledge/conversations/${id}`) return route.fulfill({ json: { ...summary, state } })
    if (path === '/api/knowledge/conversations') return route.fulfill({ json: route.request().method() === 'POST' ? summary : { items: [summary], nextCursor: null } })
    return route.fulfill({ json: [] })
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
    await page.getByRole('link', { name: '1', exact: true }).click()
    await expect(right).toBeVisible(); await expect(right.getByText('PaymentService.java', { exact: true })).toBeVisible()
    if (width >= 1600) await expect(left).toBeVisible(); else await expect(left).not.toBeVisible()
    if (width < 1100) { await expect(right).toHaveAttribute('aria-modal', 'true'); await page.keyboard.press('Shift+Tab'); expect(await right.evaluate(el => el.contains(document.activeElement))).toBe(true) }
    await page.screenshot({ path: `test-results/knowledge-citation-${width}.png`, fullPage: true })
    await right.getByRole('button', { name: '关闭引用详情' }).click(); await expect(right).not.toBeVisible()
    if (width >= 1600) await left.getByRole('button', { name: '关闭来源' }).click()
    expect(await page.locator('body').evaluate(el => el.scrollWidth <= window.innerWidth)).toBe(true)
    await page.getByRole('textbox', { name: '向项目提问' }).fill('继续解释审批流程')
    await page.getByRole('button', { name: '发送', exact: false }).click()
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '停止生成', exact: true }).click(); await expect(page.getByText('已停止生成，以上为未完成回答')).toBeVisible()
    expect(errors).toEqual([])
  })
}
test('新对话默认模型、来源与深层历史路由', async ({ page }) => {
  await fixture(page); await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: '让项目知识，成为答案' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '问答模型' })).toHaveValue('local/model')
  await page.getByRole('button', { name: '历史对话', exact: true }).click()
  await page.getByRole('link', { name: /付款流程如何工作/ }).click()
  await expect(page).toHaveURL(`/knowledge/${id}`); await expect(page.getByRole('combobox', { name: '选择项目' })).toBeDisabled()
  await page.reload(); await expect(page.getByText('付款流程如何工作？', { exact: true })).toBeVisible()
})
