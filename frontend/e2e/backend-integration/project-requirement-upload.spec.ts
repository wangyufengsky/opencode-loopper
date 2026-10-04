import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import type { KnowledgeConversation, Project } from '../../src/types/domain'
import type { WorkflowReceipt, WorkflowRequirement, WorkflowUpload, WorkflowUploadRequest, WorkflowFile } from '../../src/types/workflow'
import { action, closeSidebar, documentPanel, env, evidence, headers, json, multipart, observeWrites, openRequirement, openSidebar, post, projectFixture, requirementFixture, screenshots, verifyIsolation } from './support'
import { brokenDocx, docxFile, sha256, txtFile } from './files'
import { observeW2Resources, immediateW2Exit, assertW2Disposed } from '../w2/resources'

test.beforeAll(async ({ request }) => { await verifyIsolation(request) })

test('real Spring project registration, dirty Stay, and document-path CAS conflict retain the draft', async ({ page, request }, info) => {
  const rootPath = join(env.projectRoot, `ui-project-${randomUUID()}`)
  await mkdir(join(rootPath, 'docs'), { recursive: true }); await mkdir(join(rootPath, 'server-docs'))
  const observed = observeWrites(page), name = `隔离浏览器项目 ${randomUUID().slice(0, 8)}`
  await page.goto('/projects'); await action(page, 'project.register').first().click()
  await page.getByRole('textbox', { name: '项目名称', exact: true }).fill(name)
  await page.getByRole('textbox', { name: '项目根路径', exact: true }).fill(rootPath)
  await page.locator('.app-sidebar a[href="/tasks"]').click()
  await expect(page.getByRole('dialog')).toBeVisible(); await action(page, 'ui.stay').click()
  await expect(page).toHaveURL(/\/projects$/); await expect(page.getByRole('textbox', { name: '项目名称', exact: true })).toHaveValue(name)
  const create = page.waitForResponse(response => new URL(response.url()).pathname === '/api/projects' && response.request().method() === 'POST')
  await page.locator('form [data-semantic="project.register"]').click()
  const createdResponse = await create; expect(createdResponse.status()).toBe(201); const project: Project = await createdResponse.json()
  expect(project.rootPath).toBe(rootPath); expect(project.name).toBe(name)
  await expect(page.locator(`#project-${project.id}`)).toBeVisible()
  await page.locator(`#project-${project.id} [data-semantic="selection.select"]`).click()
  await action(page, 'project.editDocumentPath').click()
  await page.getByRole('textbox', { name: '项目文档路径', exact: true }).fill('docs')
  const external = await request.put(`/api/projects/${project.id}/document-path`, { headers, data: { version: project.version, documentPath: 'server-docs' } })
  expect(external.status()).toBe(200)
  const current: Project = await external.json(); expect(current.version).toBeGreaterThan(project.version!)
  const conflict = page.waitForResponse(response => response.url().includes(`/projects/${project.id}/document-path`) && response.request().method() === 'PUT')
  await page.locator('[data-semantic="ui.save"]').filter({ visible: true }).click()
  const conflictResponse = await conflict; expect(conflictResponse.status()).toBe(409)
  await expect(page.getByRole('textbox', { name: '项目文档路径', exact: true })).toHaveValue('docs')
  await expect(page.getByRole('alert').filter({ hasText: '项目设置已更新' })).toBeVisible()
  expect((await json<Project>(request, `/api/projects/${project.id}`)).documentPath).toBe(join(rootPath, 'server-docs'))
  const writesBeforeSkins = observed.writes.length; await screenshots(page, info, 'project-cas-conflict')
  expect(observed.writes).toHaveLength(writesBeforeSkins); expect(observed.unexpected).toEqual([]); expect(observed.errors).toEqual([])
  await evidence(info, 'project-cas', { projectId: project.id, rootPath, originalVersion: project.version, currentVersion: current.version, conflict: await conflictResponse.json(), dirtyValue: 'docs', ...observed })
})

test('real React four-field requirement create and explicit graph save persist on Spring without Start', async ({ page, request }, info) => {
  const seed = await requirementFixture(request, 'requirement-create'), observed = observeWrites(page)
  await page.goto(`/requirements/new?projectId=${seed.project.id}&template=${seed.template.id}`)
  const title = `浏览器创建需求 ${randomUUID().slice(0, 8)}`, objective = '只创建与保存人工计划，不开始执行。'
  await expect(page.locator('#workflow-requirement-title')).toBeEnabled()
  await page.locator('#workflow-requirement-title').fill(title); await page.locator('#workflow-requirement-objective').fill(objective)
  await page.locator('.app-sidebar a[href="/projects"]').click(); await expect(page.getByRole('dialog')).toBeVisible(); await action(page, 'ui.stay').click()
  await expect(page.locator('#workflow-requirement-title')).toHaveValue(title)
  const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/workflows/requirements' && response.request().method() === 'POST')
  await action(page, 'workflow.createRequirement').click()
  const response = await created; expect(response.ok()).toBe(true); const receipt: WorkflowReceipt = await response.json()
  await expect(page).toHaveURL(new RegExp(`/requirements/${receipt.id}$`))
  const persisted = await json<WorkflowRequirement>(request, `/api/workflows/requirements/${receipt.id}`)
  expect(persisted).toMatchObject({ title, objective, projectId: seed.project.id, sourceTemplateId: seed.template.id, sourceRevision: seed.template.revision, state: 'PLANNING' })
  expect(response.request().postDataJSON()).toEqual({ requestKey: expect.any(String), projectId: seed.project.id, templateId: seed.template.id, templateRevision: seed.template.revision, title, objective })
  await page.locator('article.workflow-node').click()
  const nodeTitle = page.getByRole('textbox', { name: '名称', exact: true })
  await nodeTitle.fill('浏览器已保存人工节点')
  await openSidebar(page)
  await page.locator('.app-sidebar a[href="/projects"]').click(); await expect(page.getByRole('dialog', { name: '离开当前页面', exact: true })).toBeVisible(); await action(page, 'ui.stay').click()
  await closeSidebar(page)
  await expect(nodeTitle).toHaveValue('浏览器已保存人工节点')
  const saved = page.waitForResponse(value => value.url().includes(`/requirements/${receipt.id}/layout`) && value.request().method() === 'PUT')
  await action(page, 'workflow.savePlanning').click(); expect((await saved).ok()).toBe(true)
  const updated = await json<WorkflowRequirement>(request, `/api/workflows/requirements/${receipt.id}`)
  expect(updated.graph.nodes[0]?.title).toBe('浏览器已保存人工节点'); expect(updated.revision).toBeGreaterThan(persisted.revision)
  await page.reload(); await expect(page.locator('article.workflow-node')).toContainText('浏览器已保存人工节点')
  expect(observed.writes.filter(write => write.path === '/api/workflows/requirements')).toHaveLength(1)
  expect(observed.writes.filter(write => write.path.endsWith('/plan'))).toHaveLength(1)
  expect(observed.writes.filter(write => write.path.endsWith('/layout'))).toHaveLength(1)
  expect(observed.unexpected).toEqual([]); expect(observed.errors).toEqual([])
  await screenshots(page, info, 'requirement-persisted'); await evidence(info, 'requirement-create-save', { receipt, original: persisted, saved: updated, ...observed })
})

test('real DOCX parser, ordered original bytes, identical upload replay and changed-body 409', async ({ page, request }, info) => {
  const { requirement } = await requirementFixture(request, 'docx-positive'), observed = observeWrites(page)
  const files = [docxFile('合成原文标记-FIRST', '第一份合成需求.docx'), docxFile('合成原文标记-SECOND', '第二份合成需求.docx')]
  await openRequirement(page, requirement.id, requirement.title); const panel = await documentPanel(page)
  await panel.locator('input[type="file"]').setInputFiles(files)
  await expect(panel).toContainText('第一份合成需求.docx、第二份合成需求.docx')
  const upload = page.waitForResponse(response => response.url().endsWith(`/requirements/${requirement.id}/documents`) && response.request().method() === 'POST')
  await panel.locator('[data-semantic="workflow.uploadAndSelect"]').click()
  const response = await upload; expect(response.status()).toBe(200); const original: WorkflowUpload = await response.json()
  expect(original.ready).toBe(true); expect(original.resume).toBeNull()
  expect(original.originals.map(file => ({ name: file.filename, sha256: file.sha256, size: file.sizeBytes }))).toEqual(files.map(file => ({ name: file.name, sha256: sha256(file.buffer), size: file.buffer.length })))
  await expect(panel).toContainText('已选 2 份文档')
  await panel.locator('[data-semantic="ui.open"][aria-label*="解析内容"]').click()
  const sectionButtons = panel.locator('[data-semantic="ui.open"][aria-label*="文档章节"]')
  await expect(sectionButtons.first()).toBeVisible(); await sectionButtons.first().click()
  await expect(panel).toContainText('合成原文标记-FIRST')
  const filesPage = await json<{ items: WorkflowFile[] }>(request, `/api/workflows/requirements/${requirement.id}/documents/${original.id}/files`)
  expect(filesPage.items.filter(file => file.path.startsWith('parsed/')).length).toBeGreaterThanOrEqual(2)
  for (const [index, file] of original.originals.entries()) {
    const bytes = await request.get(`/api/workflows/requirements/${requirement.id}/documents/${original.id}/file?${new URLSearchParams({ path: file.path })}`)
    expect(bytes.ok()).toBe(true); expect(sha256(await bytes.body())).toBe(sha256(files[index]!.buffer))
  }
  // Extract the actual JSON metadata part, rather than manufacturing a new request identity.
  const body = response.request().postDataBuffer()!.toString('utf8'), metadataMatch = body.match(/\{\s*"requestKey"\s*:\s*"[^"\r\n]+"\s*,\s*"expectedVersion"\s*:\s*\d+\s*,\s*"expectedRevision"\s*:\s*\d+\s*\}/)
  expect(metadataMatch).not.toBeNull(); const metadata: WorkflowUploadRequest = JSON.parse(metadataMatch![0])
  const replay = await request.post(`/api/workflows/requirements/${requirement.id}/documents`, multipart(metadata, files))
  expect(replay.ok()).toBe(true); expect(await replay.json()).toEqual(original)
  const conflict = await request.post(`/api/workflows/requirements/${requirement.id}/documents`, multipart(metadata, [files[1]!, files[0]!]))
  expect(conflict.status()).toBe(409); expect(await conflict.json()).toMatchObject({ errorCode: 'WORKFLOW_REQUEST_CONFLICT' })
  const uploads = await json<{ items: WorkflowUpload[] }>(request, `/api/workflows/requirements/${requirement.id}/documents`)
  expect(uploads.items.map(row => row.id)).toEqual([original.id])
  const before = observed.writes.length; await screenshots(page, info, 'docx-parsed'); expect(observed.writes).toHaveLength(before)
  expect(observed.unexpected).toEqual([]); expect(observed.errors).toEqual([])
  await evidence(info, 'docx-identity', { requirementId: requirement.id, metadata, original, files: files.map(file => ({ name: file.name, size: file.buffer.length, sha256: sha256(file.buffer) })), replayId: (await replay.json()).id, reversedOrderStatus: conflict.status(), ...observed })
})

test('TXT rejection and actual broken DOCX parser error remain visible before explicit valid reselection', async ({ page, request }, info) => {
  const { requirement } = await requirementFixture(request, 'parse-errors'), observed = observeWrites(page)
  await openRequirement(page, requirement.id, requirement.title); const panel = await documentPanel(page), input = panel.locator('input[type="file"]')
  await input.setInputFiles(txtFile('只在隔离测试中生成的文本'))
  await expect(panel.getByRole('alert')).toContainText(/DOCX|Markdown/)
  expect(observed.writes.filter(write => write.path.endsWith('/documents'))).toHaveLength(0)
  // Separate protocol negative: the real Spring parser also rejects TXT. This is
  // an explicit API test request, not a UI upload or an intercepted response.
  const txtRejected = await request.post(`/api/workflows/requirements/${requirement.id}/documents`, multipart({ requestKey: randomUUID(), expectedVersion: requirement.version, expectedRevision: requirement.revision }, [txtFile('合成 TXT 协议拒绝负控')]))
  expect(txtRejected.status()).toBe(400)
  expect(await txtRejected.json()).toMatchObject({ errorCode: 'DOCUMENT_TEMPLATE_FORMAT' })
  await input.setInputFiles(brokenDocx()); await expect(panel).toContainText('待上传：损坏的合成需求.docx')
  const rejected = page.waitForResponse(response => response.url().endsWith(`/requirements/${requirement.id}/documents`) && response.request().method() === 'POST')
  await panel.locator('[data-semantic="workflow.uploadAndSelect"]').click()
  const failure = await rejected; expect(failure.status()).toBe(400)
  await expect(panel.getByRole('alert').filter({ hasText: '文档无法解析' }).first()).toBeVisible()
  await expect(panel).toContainText('损坏的合成需求.docx')
  expect((await json<{ items: WorkflowUpload[] }>(request, `/api/workflows/requirements/${requirement.id}/documents`)).items).toEqual([])
  await screenshots(page, info, 'docx-parser-error')
  await input.setInputFiles(docxFile('显式纠正后的合成文档'))
  const corrected = page.waitForResponse(response => response.url().endsWith(`/requirements/${requirement.id}/documents`) && response.request().method() === 'POST')
  await panel.locator('[data-semantic="workflow.uploadAndSelect"]').click()
  const result = await corrected; expect(result.status()).toBe(200); await expect(panel).toContainText('已选 1 份文档')
  expect(observed.writes.filter(write => write.path.endsWith('/documents'))).toHaveLength(2)
  expect(observed.unexpected).toEqual([]); expect(observed.errors).toEqual([])
  await evidence(info, 'parser-error-recovery', { requirementId: requirement.id, txtApiRejection: await txtRejected.json(), rejected: await failure.json(), corrected: await result.json(), invalidTxtUiSentNoPost: true, partialDiskRecoveryExercised: false, ...observed })
})

test('real idle Knowledge SSE and Requirement REST polling clean up at the first SPA-exit snapshot', async ({ page, request }, info) => {
  const project = await projectFixture(request, 'idle-sse'), conversationId = randomUUID()
  const created = await post<KnowledgeConversation>(request, '/api/knowledge/conversations', {
    id: conversationId, projectId: project.id, title: '隔离 idle SSE · 不发送消息', sourceIds: ['documents'], timezone: 'Etc/UTC',
  })
  expect(created.id).toBe(conversationId); expect(created.state).toBe('IDLE')
  await observeW2Resources(page)
  await page.addInitScript(() => {
    const Native = window.EventSource, rows: { source: EventSource; url: string; closeCalls: number; opened: boolean }[] = []
    window.EventSource = class extends Native {
      constructor(url: string | URL, options?: EventSourceInit) { super(url, options); const row = { source: this, url: this.url, closeCalls: 0, opened: false }; rows.push(row); this.addEventListener('open', () => { row.opened = true }) }
      close() { rows.find(row => row.source === this)!.closeCalls++; super.close() }
    }
    Object.defineProperty(window, '__integrationStreams', { value: () => rows.map(row => ({ url: row.url, closeCalls: row.closeCalls, opened: row.opened, readyState: row.source.readyState })) })
  })
  const observed = observeWrites(page)
  // Initialize the runner on a real read-only predecessor, never on the measured
  // Knowledge owner. The frozen ledger keeps its original callback identities.
  await page.goto(`/knowledge/history?project=${project.id}`)
  await expect(page.locator('[data-react-page] h1')).toHaveText('历史对话')
  const historyWarm = await page.locator('html').evaluate((element, id) => {
    const host = document.querySelector('[data-app-route-owner]')
    if (!host) throw new Error('Real predecessor route owner is missing')
    Object.defineProperty(window, '__integrationPreviousHost', { value: host, configurable: true })
    const streams = (window as unknown as { __integrationStreams(): { url: string }[] }).__integrationStreams()
    return { tagName: element.tagName, path: location.pathname, previousHostConnected: host.isConnected,
      documentIdentity: window.__w2Resources.snapshot().documentIdentity,
      knowledgeWorkspacePresent: !!document.querySelector('[data-w3-workspace="knowledge"]'),
      targetStreamPresent: streams.some(row => row.url.endsWith(`/knowledge/conversations/${id}/events`)) }
  }, conversationId)
  expect(historyWarm).toMatchObject({ tagName: 'HTML', path: '/knowledge/history', previousHostConnected: true, knowledgeWorkspacePresent: false, targetStreamPresent: false })
  await page.locator('.w2-secondary-select').filter({ hasText: created.title }).click()
  const loaded = page.waitForResponse(response => new URL(response.url()).pathname === `/api/knowledge/conversations/${conversationId}` && response.request().method() === 'GET')
  await action(page, 'ui.open').click()
  const opened: KnowledgeConversation = await (await loaded).json()
  expect(opened).toMatchObject({ id: conversationId, projectId: project.id, title: created.title, model: created.model, state: 'IDLE' })
  await expect(page).toHaveURL(new RegExp(`/knowledge/${conversationId}$`))
  await expect(page.getByRole('textbox', { name: '向项目提问', exact: true })).toBeVisible()
  const hostTransition = await page.evaluate(() => {
    const previous = (window as unknown as { __integrationPreviousHost?: Element }).__integrationPreviousHost
    const current = document.querySelector('[data-app-route-owner]')
    if (!previous || !current) throw new Error('Real predecessor / Knowledge owner identity is missing')
    const result = { hostsDiffer: previous !== current, previousHostConnected: previous.isConnected, currentHostConnected: current.isConnected,
      documentIdentity: window.__w2Resources.snapshot().documentIdentity, path: location.pathname }
    delete (window as unknown as { __integrationPreviousHost?: Element }).__integrationPreviousHost
    return result
  })
  expect(hostTransition).toEqual({ hostsDiffer: true, previousHostConnected: false, currentHostConnected: true, documentIdentity: historyWarm.documentIdentity, path: `/knowledge/${conversationId}` })
  await evidence(info, 'sse-observation-preparation', { historyWarm, hostTransition, opened, UIWrites: observed.writes })
  expect(observed.writes).toEqual([])
  await page.waitForFunction(id => (window as unknown as { __integrationStreams(): { opened: boolean; url: string }[] }).__integrationStreams().some(row => row.opened && row.url.endsWith(`/knowledge/conversations/${id}/events`)), conversationId)
  await page.evaluate(() => window.__w2Resources.begin()); const knowledgeBefore = await page.evaluate(() => window.__w2Resources.snapshot())
  const knowledgeAfter = await page.evaluate(() => new Promise<{ resources: ReturnType<typeof window.__w2Resources.snapshot>; streams: { url: string; closeCalls: number; opened: boolean; readyState: number }[] }>(resolve => {
    const root = document.querySelector('[data-react-page]')!, observer = new MutationObserver(() => { if (!root.isConnected) { observer.disconnect(); resolve({ resources: window.__w2Resources.snapshot(), streams: (window as unknown as { __integrationStreams(): { url: string; closeCalls: number; opened: boolean; readyState: number }[] }).__integrationStreams() }) } })
    observer.observe(document.body, { childList: true, subtree: true }); document.querySelector<HTMLAnchorElement>('.app-sidebar a[href="/template-tasks"]')!.click()
  }))
  assertW2Disposed(knowledgeBefore, knowledgeAfter.resources)
  const ownedStreams = knowledgeAfter.streams.filter(row => row.url.endsWith(`/knowledge/conversations/${conversationId}/events`))
  expect(ownedStreams).toHaveLength(1); expect(ownedStreams[0]).toMatchObject({ opened: true, closeCalls: 1, readyState: 2 })
  const { requirement } = await requirementFixture(request, 'poll-cleanup')
  await openRequirement(page, requirement.id, requirement.title)
  await openSidebar(page)
  await page.evaluate(() => window.__w2Resources.begin())
  await page.waitForFunction(() => window.__w2Resources.snapshot().timers.length > 0)
  const requirementBefore = await page.evaluate(() => window.__w2Resources.snapshot())
  // The real requirement owner uses a recursive 2.5 s timeout, not an interval or SSE.
  expect(requirementBefore.timers.length).toBeGreaterThan(0)
  const requirementAfter = await immediateW2Exit(page); assertW2Disposed(requirementBefore, requirementAfter)
  expect(observed.unexpected).toEqual([]); expect(observed.errors).toEqual([])
  await evidence(info, 'sse-poll-first-exit', { conversationId, requirementId: requirement.id, created, historyWarm, hostTransition, opened, knowledgeBefore, knowledgeAfter, requirementBefore, requirementAfter, measurement: 'first MutationObserver snapshot after original React root detached; no natural up/move or cleanup delay; runner initialized by read-only predecessor before target owner mounted', heapOrGcProven: false, ...observed })
})
