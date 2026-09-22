import { expect, test, type Page } from '@playwright/test'
import { pptAgent, pptCapabilities, pptDeck, pptDocument, pptPlan } from '../src/components/ppt/pptTestFixtures'
import type { PptJob, PptMessage, PptOperation } from '../src/types/ppt'
const documentId = '11111111-1111-4111-8111-111111111111'
async function fixture(page: Page, phase = 'REVIEW') {
  let document = { ...pptDocument(documentId), phase }, deck = pptDeck(), plan = pptPlan(), agent = pptAgent()
  const operations: { expectedRevision: number; operations: PptOperation[] }[] = [], messages: PptMessage[] = [], jobs: PptJob[] = [], actions: string[] = [], savedPlans: typeof plan[] = []
  await page.context().route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method(), body = method === 'POST' && route.request().headers()['content-type']?.includes('application/json') ? route.request().postDataJSON() : null
    if (path.includes('/actions/')) actions.push(path)
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/ppt/capabilities') return route.fulfill({ json: pptCapabilities() })
    if (path === '/api/ppt/documents') {
      if (method === 'POST') { document = { ...document, ...body, phase: 'BRIEFING' }; return route.fulfill({ json: document }) }
      return route.fulfill({ json: { items: url.searchParams.get('archive') === 'archived' ? document.archived ? [document] : [] : document.archived ? [] : [document], nextCursor: null } })
    }
    if (path === '/api/projects/summaries') return route.fulfill({ json: [] })
    if (path === '/api/settings/models') return route.fulfill({ json: [{ id: 'local/model', label: '内网模型' }] })
    if (path.endsWith('/deck')) return route.fulfill({ json: deck })
    if (path.endsWith('/plan')) { if (method === 'POST') { plan = body.plan; savedPlans.push(plan); document.revision++; } return route.fulfill({ json: { revision: document.revision, plan } }) }
    if (path.endsWith('/sources')) return route.fulfill({ json: { sources: [], assets: [] } })
    if (path.endsWith('/agent')) return route.fulfill({ json: agent })
    if (path.endsWith('/revisions')) return route.fulfill({ json: { items: [{ revision: document.revision, reason: '编辑页面', createdAt: document.createdAt }], nextCursor: null } })
    if (path.endsWith('/messages')) {
      if (method === 'POST') { const message: PptMessage = { id: `message-${messages.length}`, documentId, ...body, answer: '我会保留其他页面，只调整指定范围。', state: 'COMPLETED', detail: '', version: 0, createdAt: document.createdAt, updatedAt: document.updatedAt, questions: [] }; messages.push(message); return route.fulfill({ json: message }) }
      return route.fulfill({ json: { items: messages, nextCursor: null } })
    }
    if (path.endsWith('/operations')) {
      operations.push(body)
      if (body.expectedRevision !== document.revision) return route.fulfill({ status: 409, json: { detail: '页面已修改，请重新读取' } })
      for (const operation of body.operations as PptOperation[]) {
        const slide = deck.slides.find(value => value.id === operation.slideId)
        if (operation.op === 'update_element' && slide) { const element = slide.elements.find(value => value.id === operation.elementId); if (element) Object.assign(element, operation.patch) }
        if (operation.op === 'update_slide' && slide) Object.assign(slide, operation.patch)
        if (operation.op === 'apply_theme') deck.theme = operation.theme!
      }
      document.revision++; document.phase = 'REVIEW'; return route.fulfill({ json: { revision: document.revision, deck, createdIds: {} } })
    }
    if (path.endsWith('/checks')) return route.fulfill({ json: { issues: [] } })
    if (path.endsWith('/jobs')) {
      if (method === 'POST') { const job: PptJob = { id: `job-${jobs.length}`, documentId, ...body, state: 'COMPLETED', completed: deck.slides.length, total: deck.slides.length, detail: '', artifacts: body.kind === 'EXPORT' ? [{ id: 'export', name: '季度汇报.pptx', mediaType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', url: `/api/ppt/documents/${documentId}/artifacts/export` }] : [], createdAt: document.createdAt }; jobs.unshift(job); if (body.kind === 'EXPORT') document.phase = 'EXPORTED'; return route.fulfill({ json: job }) }
      return route.fulfill({ json: jobs })
    }
    if (path.includes('/artifacts/')) return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', headers: { 'Content-Disposition': 'attachment; filename="fixture.pptx"' }, body: 'fixture-export' })
    if (path.endsWith('/actions/confirm-direction')) { document.phase = 'DESIGN'; return route.fulfill({ json: document }) }
    if (path.endsWith('/actions/start-production')) { document.phase = 'PRODUCING'; agent = { ...agent, state: body?.useAgent === false ? 'IDLE' : 'RUNNING' }; return route.fulfill({ json: document }) }
    if (path.endsWith('/actions/archive')) { document.archived = body.archived; return route.fulfill({ json: document }) }
    if (path.endsWith('/stop')) { agent = { ...agent, state: 'STOPPING' }; return route.fulfill({ json: agent }) }
    if (path === `/api/ppt/documents/${documentId}` || path === `/api/ppt/documents/${document.id}`) return route.fulfill({ json: document })
    return route.fulfill({ json: [] })
  })
  return { operations, messages, jobs, savedPlans, actions }
}

test('独立工作室保存对象、冻结自然语言范围并导出当前版本', async ({ page }) => {
  const state = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/ppt/${documentId}`)
  await expect(page.getByRole('heading', { name: '季度汇报', exact: true })).toBeVisible()
  const object = page.getByRole('button', { name: '文本框：本季度核心成果' }); await object.click(); await expect(object).toBeFocused(); await page.keyboard.press('ArrowRight')
  await expect.poll(() => state.operations.length).toBe(1); expect(state.operations[0]!.expectedRevision).toBe(3); expect(state.operations[0]!.operations[0]!.patch).toMatchObject({ x: 81, y: 70 })
  await page.locator('.ppt-right-panel').getByRole('button', { name: 'PPT 助手', exact: true }).click()
  await page.getByLabel('向 PPT 助手发送要求').fill('缩短选中的标题'); await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => state.messages.length).toBe(1); expect(state.messages[0]!.scope).toEqual({ kind: 'ELEMENT', slideId: 'slide-1', elementId: 'text-1' })
  await page.getByRole('button', { name: '导出 PPTX', exact: true }).click(); await page.getByRole('button', { name: '版本与导出' }).click()
  await expect(page.getByRole('link', { name: '季度汇报.pptx' })).toBeVisible(); expect(state.jobs[0]!.revision).toBe(4)
  const downloaded = page.waitForEvent('download'); await page.getByRole('link', { name: '季度汇报.pptx' }).click(); const download = await downloaded; expect(download.suggestedFilename()).toBeTruthy(); expect(await download.failure()).toBeNull(); const stream = await download.createReadStream(); const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk)); expect(Buffer.concat(chunks).toString()).toBe('fixture-export')
  await page.screenshot({ path: 'test-results/ppt-workspace-desktop.png', fullPage: true })
})

test('两次方案确认按服务端推进，开始制作只提交一次动作', async ({ page }) => {
  const state = await fixture(page, 'DIRECTION'); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/ppt/${documentId}`)
  await page.getByRole('button', { name: '确认整体方向', exact: true }).click(); await expect(page.getByRole('button', { name: '确认设计并开始制作' })).toBeVisible()
  await page.getByRole('button', { name: '确认设计并开始制作' }).click(); await expect(page.getByRole('button', { name: '停止本轮', exact: true })).toBeVisible(); expect(state.messages).toHaveLength(0)
  await page.getByRole('button', { name: '停止本轮', exact: true }).click(); await expect(page.getByRole('button', { name: '正在安全暂停', exact: true })).toBeDisabled(); await expect(page.getByRole('button', { name: '导出 PPTX', exact: true })).toBeDisabled()
})

test('窄屏资料与属性可打开，页面无横向溢出且深链可恢复', async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 768, height: 1000 }); await page.goto(`/ppt/${documentId}`)
  await page.getByRole('button', { name: '资料与页面', exact: true }).click(); await expect(page.locator('.ppt-left-panel')).toBeVisible(); await page.locator('.ppt-left-panel').getByRole('button', { name: '收起', exact: true }).click()
  await page.getByRole('button', { name: '助手与属性', exact: true }).click(); await expect(page.locator('.ppt-right-panel')).toBeVisible(); await page.locator('.ppt-right-panel').getByRole('button', { name: '收起', exact: true }).click()
  expect(await page.locator('body').evaluate(element => element.scrollWidth <= window.innerWidth)).toBe(true)
  await page.reload(); await expect(page.getByRole('heading', { name: '季度汇报', exact: true })).toBeVisible(); await page.screenshot({ path: 'test-results/ppt-workspace-narrow.png', fullPage: true })
})

test('七个方案模块可编辑并自动保存，刷新保留方案且不会自动开始制作', async ({ page }) => {
  const state = await fixture(page, 'BRIEFING'); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/ppt/${documentId}`)
  await page.getByLabel('受众', { exact: true }).fill('内网项目评审委员会')
  await expect.poll(() => state.savedPlans.length).toBe(1)
  await expect(page.locator('.ppt-plan-footer')).toContainText('已自动保存')
  expect(state.savedPlans[0]!.brief.audience).toBe('内网项目评审委员会')
  await page.screenshot({ path: 'test-results/ppt-plan-brief-autosaved.png', fullPage: true })
  const modules = [ ['叙事结构', 'narrative'], ['页面内容', 'slides'], ['视觉规范', 'visual'], ['图表素材', 'assets'], ['演讲辅助', 'notes'], ['交付设置', 'delivery'] ] as const
  for (const [title, id] of modules) {
    await page.getByRole('navigation', { name: '方案模块' }).getByRole('button', { name: title, exact: true }).click()
    if (id === 'visual') await expect(page.getByLabel('页面比例', { exact: true })).toHaveAttribute('readonly')
    if (id === 'delivery') { await page.getByLabel('文件名称', { exact: true }).fill('内网项目汇报.pptx'); await expect.poll(() => state.savedPlans.length).toBe(2); await expect(page.locator('.ppt-plan-footer')).toContainText('已自动保存') }
    await page.screenshot({ path: `test-results/ppt-plan-${id}.png`, fullPage: true })
  }
  expect(state.messages).toHaveLength(0)
  await page.reload(); await expect(page.getByLabel('受众', { exact: true })).toHaveValue('内网项目评审委员会')
  expect(state.actions).toHaveLength(0)
})
