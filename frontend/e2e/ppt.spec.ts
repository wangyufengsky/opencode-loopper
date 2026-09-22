import { expect, test, type Page } from '@playwright/test'
import {
  pptAgent,
  pptCapabilities,
  pptDeck,
  pptDocument,
  pptGeneration,
  pptPlan,
} from '../src/components/ppt/pptTestFixtures'
import type { PptGeneration, PptJob, PptMessage, PptOperation, PptPhase } from '../src/types/ppt'

const documentId = '11111111-1111-4111-8111-111111111111'
const presentationType = 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
const samplePreview =
  '<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540"><rect width="960" height="540" fill="#f8fafc"/><rect width="12" height="540" fill="#2563eb"/><text x="80" y="120" fill="#172554" font-size="38" font-family="sans-serif">本季度核心成果</text><text x="80" y="170" fill="#64748b" font-size="18" font-family="sans-serif">季度经营汇报 · 2026</text><rect x="80" y="225" width="240" height="180" rx="12" fill="#eff6ff"/><rect x="350" y="225" width="240" height="180" rx="12" fill="#eff6ff"/><rect x="620" y="225" width="240" height="180" rx="12" fill="#eff6ff"/><text x="110" y="290" fill="#2563eb" font-size="38">32%</text><text x="380" y="290" fill="#2563eb" font-size="38">18</text><text x="650" y="290" fill="#2563eb" font-size="38">96%</text><text x="110" y="350" fill="#172554" font-size="20">业务增长</text><text x="380" y="350" fill="#172554" font-size="20">项目交付</text><text x="650" y="350" fill="#172554" font-size="20">客户满意度</text></svg>'

async function fixture(page: Page, initial: 'ready' | 'draft' = 'ready') {
  let document = {
    ...pptDocument(documentId),
    phase: (initial === 'ready' ? 'EXPORTED' : 'BRIEFING') as PptPhase,
  }
  let deck = pptDeck(),
    plan = pptPlan(),
    agent = pptAgent()
  let generation: PptGeneration | null =
    initial === 'ready' ? { ...pptGeneration('COMPLETED'), step: 'EXPORT' } : null
  const operations: { expectedRevision: number; operations: PptOperation[] }[] = []
  const messages: PptMessage[] = [],
    jobs: PptJob[] = [],
    actions: string[] = [],
    savedPlans: (typeof plan)[] = []
  const generations: { prompt: string; expectedRevision: number; idempotencyKey: string }[] = []
  const resumes: unknown[] = [],
    replies: unknown[] = [],
    uploads: string[] = []
  function artifacts(kind: 'PREVIEW' | 'EXPORT') {
    return kind === 'EXPORT'
      ? [
          {
            id: `export-${document.revision}`,
            name: '季度汇报.pptx',
            mediaType: presentationType,
            url: '',
          },
        ]
      : deck.slides.map((slide) => ({
          id: `preview-${document.revision}-${slide.id}`,
          slideId: slide.id,
          name: `${slide.title}.png`,
          mediaType: 'image/png',
          url: '',
        }))
  }
  function job(kind: 'PREVIEW' | 'EXPORT'): PptJob {
    return {
      id: `job-${jobs.length}`,
      documentId: document.id,
      kind,
      revision: document.revision,
      state: 'COMPLETED',
      completed: kind === 'EXPORT' ? 1 : deck.slides.length,
      total: kind === 'EXPORT' ? 1 : deck.slides.length,
      detail: '',
      artifacts: artifacts(kind),
      createdAt: document.createdAt,
    }
  }
  function finish() {
    document.phase = 'EXPORTED'
    generation = {
      ...pptGeneration('COMPLETED'),
      revision: document.revision,
      step: 'EXPORT',
      detail: '演示文稿已完成',
    }
    agent = pptAgent()
    jobs.unshift(job('PREVIEW'))
    jobs.unshift(job('EXPORT'))
  }
  function question() {
    generation = { ...pptGeneration('WAITING_INPUT'), detail: '请确认这份汇报的目标受众' }
    agent = {
      ...pptAgent(),
      state: 'WAITING_INPUT',
      questions: [
        {
          id: 'question-1',
          prompt: '这份汇报主要给谁看？',
          options: ['管理层', '项目团队'],
          state: 'PENDING',
          answer: null,
          version: 2,
        },
      ],
    }
  }
  if (initial === 'ready') finish()
  await page.context().route('http://127.0.0.1:41773/api/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname,
      method = route.request().method()
    const body =
      method === 'POST' && route.request().headers()['content-type']?.includes('application/json')
        ? route.request().postDataJSON()
        : null
    if (path.includes('/actions/')) actions.push(path)
    if (path.endsWith('/events'))
      return route.fulfill({
        contentType: 'text/event-stream',
        body: 'data: {"type":"connected"}\n\n',
      })
    if (path === '/api/ppt/capabilities') return route.fulfill({ json: pptCapabilities() })
    if (path === '/api/ppt/documents') {
      if (method === 'POST') {
        document = { ...document, ...body, phase: 'BRIEFING' }
        deck = { ...deck, slides: [] }
        jobs.length = 0
        generation = null
        return route.fulfill({ json: document })
      }
      return route.fulfill({
        json: {
          items:
            url.searchParams.get('archive') === 'archived'
              ? document.archived
                ? [document]
                : []
              : document.archived
                ? []
                : [document],
          nextCursor: null,
        },
      })
    }
    if (path.endsWith('/generate/resume')) {
      resumes.push(body)
      generation = { ...pptGeneration('PRODUCING'), detail: '正在继续制作' }
      return route.fulfill({ json: generation })
    }
    if (path.endsWith('/generate')) {
      generations.push(body)
      generation = { ...pptGeneration(), documentId: document.id }
      document.phase = 'BRIEFING'
      return route.fulfill({ json: generation })
    }
    if (path.endsWith('/generation')) return route.fulfill({ json: generation })
    if (path.includes('/questions/') && path.endsWith('/reply')) {
      replies.push(body)
      deck = pptDeck()
      finish()
      return route.fulfill({
        json: { ...messages[0], id: 'reply-1', state: 'COMPLETED', questions: [] },
      })
    }
    if (path.endsWith('/deck')) return route.fulfill({ json: deck })
    if (path.endsWith('/plan')) {
      if (method === 'POST') {
        plan = body.plan
        savedPlans.push(plan)
        document.revision++
      }
      return route.fulfill({ json: { revision: document.revision, plan } })
    }
    if (path.endsWith('/sources') || path.endsWith('/assets')) {
      if (method === 'POST') {
        uploads.push(path)
        return route.fulfill({
          json: {
            id: 'source',
            kind: 'MARKDOWN',
            name: '材料.md',
            state: 'READY',
            detail: '',
            bytes: 10,
            sections: 1,
            limitations: [],
          },
        })
      }
      return route.fulfill({ json: { sources: [], assets: [] } })
    }
    if (path.endsWith('/agent')) return route.fulfill({ json: agent })
    if (path.endsWith('/revisions'))
      return route.fulfill({
        json: {
          items: [
            { revision: document.revision, reason: '编辑页面', createdAt: document.createdAt },
          ],
          nextCursor: null,
        },
      })
    if (path.endsWith('/messages')) {
      if (method === 'POST') {
        const message: PptMessage = {
          id: `message-${messages.length}`,
          documentId,
          ...body,
          answer: '我会保留其他页面，只调整指定范围。',
          state: 'COMPLETED',
          detail: '',
          version: 0,
          createdAt: document.createdAt,
          updatedAt: document.updatedAt,
          questions: [],
        }
        messages.push(message)
        document.revision++
        document.phase = 'PRODUCING'
        generation = { ...pptGeneration('PRODUCING'), detail: '正在根据修改意见调整页面' }
        return route.fulfill({ json: message })
      }
      return route.fulfill({ json: { items: messages, nextCursor: null } })
    }
    if (path.endsWith('/operations')) {
      operations.push(body)
      if (body.expectedRevision !== document.revision)
        return route.fulfill({ status: 409, json: { detail: '页面已修改，请重新读取' } })
      for (const operation of body.operations as PptOperation[]) {
        const slide = deck.slides.find((value) => value.id === operation.slideId)
        if (operation.op === 'update_element' && slide) {
          const element = slide.elements.find((value) => value.id === operation.elementId)
          if (element) Object.assign(element, operation.patch)
        }
        if (operation.op === 'update_slide' && slide) Object.assign(slide, operation.patch)
        if (operation.op === 'apply_theme') deck.theme = operation.theme!
      }
      document.revision++
      document.phase = 'REVIEW'
      return route.fulfill({ json: { revision: document.revision, deck, createdIds: {} } })
    }
    if (path.endsWith('/checks')) return route.fulfill({ json: { issues: [] } })
    if (path.endsWith('/jobs')) {
      if (method === 'POST') {
        const created = job(body.kind)
        jobs.unshift(created)
        if (body.kind === 'EXPORT') document.phase = 'EXPORTED'
        return route.fulfill({ json: created })
      }
      return route.fulfill({ json: jobs })
    }
    if (path.includes('/artifacts/preview-'))
      return route.fulfill({ contentType: 'image/svg+xml', body: samplePreview })
    if (path.includes('/artifacts/'))
      return route.fulfill({
        contentType: presentationType,
        headers: { 'Content-Disposition': 'attachment; filename="fixture.pptx"' },
        body: 'fixture-export',
      })
    if (path.endsWith('/actions/reopen')) {
      document.phase = 'BRIEFING'
      return route.fulfill({ json: document })
    }
    if (path.endsWith('/actions/archive')) {
      document.archived = body.archived
      return route.fulfill({ json: document })
    }
    if (path.endsWith('/stop')) {
      agent = { ...agent, state: 'STOPPING' }
      generation = { ...pptGeneration('STOPPING') }
      return route.fulfill({ json: agent })
    }
    if (path === `/api/ppt/documents/${documentId}` || path === `/api/ppt/documents/${document.id}`)
      return route.fulfill({ json: document })
    return route.fulfill({ json: [] })
  })
  return {
    operations,
    messages,
    jobs,
    savedPlans,
    actions,
    generations,
    resumes,
    replies,
    uploads,
    finish,
    question,
    stopComplete: () => {
      agent = pptAgent()
      generation = pptGeneration('STOPPED')
    },
  }
}

async function openMore(page: Page, name: string) {
  await page.getByRole('button', { name: '更多作品操作' }).click()
  await page.getByRole('button', { name, exact: true }).click()
}

test('PPT 助手显示真实思考和调用，展开状态随更新保留', async ({ page }) => {
  const state = await fixture(page, 'draft')
  state.messages.push({ id: 'activity', documentId, idempotencyKey: 'activity-key', text: '制作项目汇报', answer: '', state: 'RUNNING', detail: '', scope: { kind: 'DOCUMENT' }, expectedRevision: 0, version: 0, createdAt: '', updatedAt: '', questions: [], thinking: '先整理汇报结构。\n\n正在核对关键结论。', calls: [{ id: 'tool', tool: 'ppt_get_context', state: 'RUNNING', detail: '' }] })
  await page.goto(`/ppt/${documentId}`)
  const thinking = page.locator('section[aria-label="思考"]'), tools = page.locator('section[aria-label="工具调用"]')
  await expect(thinking.getByRole('button')).toHaveAttribute('aria-expanded', 'false')
  await expect(tools.getByRole('button')).toHaveAttribute('aria-expanded', 'false')
  await expect(tools).toContainText('查看作品与设计')
  await thinking.getByRole('button').click()
  await expect(thinking.locator('.knowledge-thinking-content')).toBeVisible()
  state.messages[0]!.thinking = '先整理汇报结构。\n\n开始检查版式。'
  await expect(thinking).toContainText('开始检查版式', { timeout: 15000 })
  await expect(thinking.getByRole('button')).toHaveAttribute('aria-expanded', 'true')
  await page.screenshot({ path: 'test-results/ppt-shared-activity.png', fullPage: true })
  state.messages[0]!.state = 'STOPPED'
  await expect(tools.locator('.knowledge-spinner')).toHaveCount(0, { timeout: 15000 })
})

test('输入需求和可选附件后一键生成，只在关键缺口提问，回答后直接完成', async ({ page }) => {
  const state = await fixture(page, 'draft')
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto('/ppt')
  await expect(page.getByRole('heading', { name: /从一个想法，.*到一份好演示。/ })).toBeVisible()
  await expect(page.getByLabel('作品名称')).toHaveCount(0)
  await page.getByLabel('你想制作什么 PPT').fill('做一份季度经营汇报，重点突出成果和下一步计划')
  await page.getByLabel('添加制作资料').setInputFiles({
    name: '材料.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# 成果\n收入增长32%'),
  })
  await page.screenshot({ path: 'test-results/ppt-redesign-entry.png', fullPage: true })
  await page.getByRole('button', { name: '生成 PPT', exact: true }).click()
  await expect(page.getByRole('heading', { name: '正在构思内容' })).toBeVisible()
  expect(state.generations).toHaveLength(1)
  expect(state.uploads).toHaveLength(1)
  expect(state.actions).toHaveLength(0)
  await expect(page.getByRole('navigation', { name: '方案模块' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '手动编辑', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '确认整体方向' })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/ppt-redesign-generating.png', fullPage: true })
  state.question()
  await expect(page.getByRole('heading', { name: '这份汇报主要给谁看？' })).toBeVisible({
    timeout: 10_000,
  })
  await page.getByRole('radio', { name: '管理层', exact: true }).check()
  await page.screenshot({ path: 'test-results/ppt-redesign-question.png', fullPage: true })
  await page.getByRole('button', { name: '回答并继续' }).click()
  await expect(page.getByRole('link', { name: '下载 PPT' })).toBeVisible()
  await expect(page.getByAltText('当前幻灯片实际预览')).toBeVisible()
  expect(state.replies).toHaveLength(1)
  expect(state.generations).toHaveLength(1)
  expect(state.actions).toHaveLength(0)
})

test('完成后默认预览与修改意见，选择对象限定修改，自动更新文件', async ({ page }) => {
  const state = await fixture(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto(`/ppt/${documentId}`)
  await expect(page.getByAltText('当前幻灯片实际预览')).toBeVisible()
  await expect(page.getByRole('button', { name: '文本框', exact: true })).toHaveCount(0)
  await expect(page.locator('.ppt-scope-chip')).toContainText('整份演示文稿')
  const object = page.getByRole('button', { name: '文本框：本季度核心成果' })
  await object.click()
  await page.keyboard.press('ArrowRight')
  expect(state.operations).toHaveLength(0)
  await page.getByLabel('向 PPT 助手发送要求').fill('缩短这个标题，保留核心结论')
  await page.getByRole('button', { name: '修改', exact: true }).click()
  await expect.poll(() => state.messages.length).toBe(1)
  expect(state.messages[0]!.scope).toEqual({
    kind: 'ELEMENT',
    slideId: 'slide-1',
    elementId: 'text-1',
  })
  await expect(page.getByRole('button', { name: '暂停', exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/ppt-redesign-revising.png', fullPage: true })
  state.finish()
  await expect(page.getByRole('link', { name: '下载 PPT' })).toBeVisible({ timeout: 10_000 })
  const downloaded = page.waitForEvent('download')
  await page.getByRole('link', { name: '下载 PPT' }).click()
  const download = await downloaded
  expect(download.suggestedFilename()).toBe('季度汇报.pptx')
  expect(await download.failure()).toBeNull()
  const stream = await download.createReadStream(),
    chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  expect(Buffer.concat(chunks).toString()).toBe('fixture-export')
  await page.getByRole('button', { name: '改为修改整份演示文稿' }).click()
  await expect(page.locator('.ppt-scope-chip')).toContainText('整份演示文稿')
  await page.screenshot({ path: 'test-results/ppt-redesign-preview.png', fullPage: true })
})

test('手动编辑可选开启，对象键盘移动保存真实坐标与版本', async ({ page }) => {
  const state = await fixture(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto(`/ppt/${documentId}`)
  await page.getByRole('button', { name: '手动编辑', exact: true }).click()
  const object = page.getByRole('button', { name: '文本框：本季度核心成果' })
  await object.click()
  await expect(object).toBeFocused()
  await expect(object).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => state.operations.length).toBe(1)
  expect(state.operations[0]!.expectedRevision).toBe(3)
  expect(state.operations[0]!.operations[0]!.patch).toMatchObject({ x: 81, y: 70 })
  await expect(page.getByLabel('横向位置')).toHaveValue('81')
  await page.screenshot({ path: 'test-results/ppt-redesign-manual.png', fullPage: true })
  await page.getByLabel('文字', { exact: true }).fill('精简后的核心成果')
  await expect(page.getByRole('button', { name: '完成编辑', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '修改意见', exact: true }).click()
  await expect.poll(() => state.operations.length).toBe(2)
  expect(state.operations[1]!.operations[0]!.patch?.text).toBe('精简后的核心成果')
  await expect(page.getByRole('button', { name: '完成编辑', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '完成编辑', exact: true }).click()
  await expect(page.locator('.ppt-editor-toolbar')).toHaveCount(0)
  await expect(page.getByLabel('向 PPT 助手发送要求')).toBeVisible()
})

test('安全暂停保持阻断，证明停止后显示继续制作且沿原工作流恢复', async ({ page }) => {
  const state = await fixture(page, 'draft')
  await page.goto(`/ppt/${documentId}`)
  await page.getByLabel('向 PPT 助手发送要求').fill('制作一份战略汇报')
  await page.getByRole('button', { name: '生成 PPT', exact: true }).click()
  await page.getByRole('button', { name: '暂停', exact: true }).click()
  await expect(page.getByRole('button', { name: '暂停', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '继续制作', exact: true })).toHaveCount(0)
  state.stopComplete()
  await expect(page.getByRole('button', { name: '继续制作', exact: true })).toBeVisible({
    timeout: 10_000,
  })
  await page.screenshot({ path: 'test-results/ppt-redesign-paused.png', fullPage: true })
  await page.getByRole('button', { name: '继续制作', exact: true }).click()
  await expect(page.getByRole('heading', { name: '正在制作页面' })).toBeVisible()
  expect(state.resumes).toHaveLength(1)
  expect(state.generations).toHaveLength(1)
})

test('详细方案移入更多入口，七模块修改自动保存且不偷偷开始制作', async ({ page }) => {
  const state = await fixture(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto(`/ppt/${documentId}`)
  await expect(page.getByRole('navigation', { name: '方案模块' })).toHaveCount(0)
  await openMore(page, '制作方案')
  await expect(page.getByRole('dialog', { name: '制作方案', exact: true })).toBeVisible()
  await expect(page.getByLabel('受众', { exact: true })).toBeDisabled()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '修改方案', exact: true }).click()
  await page.getByLabel('受众', { exact: true }).fill('内网项目评审委员会')
  await expect.poll(() => state.savedPlans.length).toBe(1)
  await expect(page.locator('.ppt-plan-footer')).toContainText('已自动保存')
  for (const title of ['叙事结构', '页面内容', '视觉规范', '图表素材', '演讲辅助', '交付设置']) {
    await page
      .getByRole('navigation', { name: '方案模块' })
      .getByRole('button', { name: title, exact: true })
      .click()
  }
  await page.getByLabel('文件名称', { exact: true }).fill('内网项目汇报.pptx')
  await expect.poll(() => state.savedPlans.length).toBe(2)
  await page.screenshot({ path: 'test-results/ppt-redesign-details.png', fullPage: true })
  expect(state.generations).toHaveLength(0)
  expect(state.messages).toHaveLength(0)
})

test('窄屏预览和助手分层呈现，无横向溢出，深链刷新保留作品', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`/ppt/${documentId}`)
  await expect(page.getByAltText('当前幻灯片实际预览')).toBeVisible()
  await page.getByRole('button', { name: '提修改意见', exact: true }).click()
  await expect(page.getByLabel('向 PPT 助手发送要求')).toBeVisible()
  await page.getByRole('button', { name: '收起助手', exact: true }).click()
  expect(
    await page.locator('body').evaluate((element) => element.scrollWidth <= window.innerWidth),
  ).toBe(true)
  await page.reload()
  await expect(page.getByRole('heading', { name: '季度汇报', exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/ppt-redesign-mobile.png', fullPage: true })
})
