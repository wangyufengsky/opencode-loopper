import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test'
import { mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import type { Project } from '../../src/types/domain'
import type { WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowRequirement, WorkflowUploadRequest } from '../../src/types/workflow'
import { integrationEnvironment, localOrigin } from './environment'
import type { SyntheticFile } from './files'

export const env = integrationEnvironment()
export const headers = { 'X-Loopper-Local-UI': '1' }
/** Read parent proof + actual read-only runtime before the first database / filesystem write. */
export async function verifyIsolation(request: APIRequestContext) {
  const path = process.env.BACKEND_INTEGRATION_ISOLATION_PROOF
  if (!path) throw new Error('BACKEND_INTEGRATION_ISOLATION_PROOF is required before integration writes')
  const proofPath = await realpath(path)
  expect(proofPath).toBe(resolve(path))
  const proof: unknown = JSON.parse(await readFile(proofPath, 'utf8'))
  if (!proof || typeof proof !== 'object' || Array.isArray(proof)) throw new Error('Invalid parent isolation manifest')
  const declaration = proof as Record<string, unknown>, root = dirname(proofPath)
  const spring = localOrigin(process.env.BACKEND_INTEGRATION_SPRING_URL, 'BACKEND_INTEGRATION_SPRING_URL')
  const expectedRevision = process.env.BACKEND_INTEGRATION_EXPECTED_REVISION ?? '11ca25a3bb764a2d80ac924350139a7087639121'
  expect(expectedRevision).toMatch(/^[a-f0-9]{40}$/)
  expect(declaration).toMatchObject({ ready: true, revision: expectedRevision, baseUrl: spring,
    opencodeMode: 'fake', model: 'fake/model', schedulingEnabled: false, startupRecoveryEnabled: false,
    runRoot: root, dataDir: join(root, 'data'), projectRoot: join(root, 'projects'),
  })
  expect(declaration.stopped).not.toBe(true)
  const hashes = declaration.runtimeHashes
  if (!hashes || typeof hashes !== 'object' || Array.isArray(hashes)) throw new Error('Missing runtimeHashes in parent isolation proof')
  const recordedHashes = hashes as Record<string, unknown>
  expect(recordedHashes.classesSha256).toMatch(/^[a-f0-9]{64}$/)
  expect(typeof recordedHashes.classFileCount).toBe('number'); expect(Number.isSafeInteger(recordedHashes.classFileCount)).toBe(true); expect(recordedHashes.classFileCount as number).toBeGreaterThan(0)
  if (!Array.isArray(recordedHashes.dependencies) || !recordedHashes.dependencies.length) throw new Error('The complete runtime dependency hash list is required')
  const dependencyPaths = new Set<string>()
  for (const row of recordedHashes.dependencies) {
    if (!row || typeof row !== 'object' || typeof row.file !== 'string' || typeof row.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(row.sha256) || !row.file.endsWith('.jar') || resolve(row.file) !== row.file) throw new Error('Invalid locked runtime dependency identity')
    const path = await realpath(row.file)
    expect(dependencyPaths.has(path)).toBe(false); dependencyPaths.add(path)
    expect(createHash('sha256').update(await readFile(path)).digest('hex'), `Runtime dependency hash: ${path}`).toBe(row.sha256)
  }
  const classes = fileURLToPath(new URL('../../../target/backend-dev/classes/', import.meta.url)), entries: [string, string][] = []
  async function inspectClasses(directory: string, prefix = '') {
    for (const item of await readdir(directory, { withFileTypes: true })) {
      const relative = prefix + item.name, path = join(directory, item.name)
      if (item.isDirectory()) await inspectClasses(path, relative + '/')
      else if (item.isFile()) entries.push([relative, createHash('sha256').update(await readFile(path)).digest('hex')])
      else throw new Error('The compiled class tree contains an unexpected symlink or special file')
    }
  }
  await inspectClasses(classes); entries.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)
  // Match the launcher's Python JSON encoding (UTF-8, compact separators).
  const encoded = JSON.stringify(entries)
  expect(entries.length).toBe(recordedHashes.classFileCount)
  expect(createHash('sha256').update(encoded).digest('hex')).toBe(recordedHashes.classesSha256)
  expect(typeof declaration.ownedJavaPid).toBe('number'); expect(Number.isSafeInteger(declaration.ownedJavaPid)).toBe(true); expect(declaration.ownedJavaPid as number).toBeGreaterThan(0)
  expect(await realpath(String(declaration.dataDir))).toBe(join(root, 'data'))
  expect(await realpath(String(declaration.projectRoot))).toBe(env.allowedRoot)
  expect(env.projectRoot).toBe(join(env.allowedRoot, 'B'))
  expect(env.projectRoot.startsWith(String(declaration.dataDir) + '/')).toBe(false)
  const runtime = await json<{ status: string; version: string; managed: boolean; model: string }>(request, '/api/runtime/opencode')
  expect(runtime).toMatchObject({ status: 'AVAILABLE', version: 'fake', managed: false, model: 'fake/model' })
  const health = await json<{ status: string }>(request, '/actuator/health'); expect(health.status).toBe('UP')
  // Persist only after all isolation checks have passed. This does not prove that
  // REST exposes dataDir; dataDir remains the parent's launch declaration.
  await mkdir(env.evidenceDir, { recursive: true })
  await writeFile(join(env.evidenceDir, 'isolation-verified.json'), JSON.stringify({ proofPath, declaration, runtime, health, UIOrigin: env.baseURL, springOrigin: spring, verification: 'parent launch declaration + actual runtime/health GET; not a SQLite path API' }, null, 2) + '\n')
}
export const action = (page: Page, key: string) => page.locator(`[data-semantic="${key}"]`).filter({ visible: true })
export async function json<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(path)
  expect(response.ok(), `${path}: ${response.status()} ${await response.text()}`).toBe(true)
  return response.json() as Promise<T>
}
export async function post<T>(request: APIRequestContext, path: string, data: unknown): Promise<T> {
  const response = await request.post(path, { headers, data })
  expect(response.ok(), `${path}: ${response.status()} ${await response.text()}`).toBe(true)
  return response.json() as Promise<T>
}
export async function projectFixture(request: APIRequestContext, name: string): Promise<Project> {
  const rootPath = join(env.projectRoot, `${name}-${randomUUID()}`)
  await mkdir(join(rootPath, 'docs'), { recursive: true })
  return post(request, '/api/projects', { name: `隔离联调 ${name}`, rootPath, description: '合成后端联调项目；禁止执行模型与外部操作', documentPath: null })
}
export function humanDocumentGraph(): WorkflowGraph {
  return { schemaVersion: 1, inputs: [{ name: 'material', title: '参考文档', kind: 'DOCUMENT', required: false }], edges: [], nodes: [{
    id: 'review', title: '人工核对文档', kind: 'HUMAN', moduleId: null, moduleVersion: 0, roleId: null, roleRevisionId: null,
    task: '人工核对合成资料。本测试不开始执行。', inputs: [{ name: 'material', source: 'REQUIREMENT', sourceId: 'material', output: null, kind: 'DOCUMENT', required: false }],
    outputs: [{ name: 'result', title: '人工结论', kind: 'TEXT', required: true }], outcomes: [],
    completion: { kind: 'HUMAN', criterion: '人工确认', expectedOutcome: null }, maxRetries: 0, pauseAfter: true, parameters: {},
  }] }
}
export const safeLayout = (): WorkflowLayout => ({ positions: { review: { x: 40, y: 40 } }, x: 32, y: 36, zoom: 1 })
export async function requirementFixture(request: APIRequestContext, name: string) {
  const project = await projectFixture(request, name)
  const template = await post<WorkflowReceipt>(request, '/api/workflows/templates', {
    requestKey: randomUUID(), title: `隔离联调流程 ${name}`, description: '只保存人工节点，不开始执行', graph: humanDocumentGraph(), layout: safeLayout(),
  })
  const created = await post<WorkflowReceipt>(request, '/api/workflows/requirements', {
    requestKey: randomUUID(), projectId: project.id, templateId: template.id, templateRevision: template.revision,
    title: `隔离联调需求 ${name}`, objective: '核对真实 Spring 回执、文档解析与路由资源；不开始执行。',
  })
  const requirement = await json<WorkflowRequirement>(request, `/api/workflows/requirements/${created.id}`)
  expect(requirement.state).toBe('PLANNING')
  expect(requirement.projectId).toBe(project.id)
  expect(requirement.diagnostics).toEqual([])
  return { project, template, requirement }
}
export async function openRequirement(page: Page, id: string) {
  await page.goto(`/requirements/${id}`)
  await expect(page.locator('[data-react-page] h1')).toContainText('需求流程')
  await expect(page.locator('article.workflow-node').filter({ hasText: '人工核对文档' })).toBeVisible()
}
export async function documentPanel(page: Page) {
  await action(page, 'workflow.flowInputs').click()
  await expect(page.getByRole('heading', { name: '需求与资料', exact: true })).toBeVisible()
  return page.getByRole('region', { name: '参考文档', exact: true })
}
export async function openSidebar(page: Page) {
  const expand = page.locator('.canvas-navigation-toggle[data-semantic="ui.expand"]')
  if (await expand.count()) await expand.click()
  await expect(page.locator('.app-sidebar a[href="/projects"]')).toBeVisible()
}
export async function closeSidebar(page: Page) {
  const collapse = page.locator('.canvas-navigation-toggle[data-semantic="ui.collapse"]')
  if (await collapse.count()) await collapse.click()
}
/** Real multipart encoder: metadata has application/json, and repeated files preserve order. */
export function multipart(metadata: WorkflowUploadRequest, files: readonly SyntheticFile[]) {
  const boundary = `LoopperIntegration${randomUUID().replaceAll('-', '')}`
  const blocks: Buffer[] = [Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="metadata"; filename="metadata.json"\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(metadata)}\r\n`)]
  for (const file of files) {
    if (/[\r\n"\\]/.test(file.name)) throw new Error('Synthetic multipart name is unsafe')
    blocks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files"; filename="${file.name}"\r\nContent-Type: ${file.mimeType}\r\n\r\n`), file.buffer, Buffer.from('\r\n'))
  }
  blocks.push(Buffer.from(`--${boundary}--\r\n`))
  return { data: Buffer.concat(blocks), headers: { ...headers, 'Content-Type': `multipart/form-data; boundary=${boundary}` } }
}
export async function evidence(info: TestInfo, name: string, value: unknown) {
  const path = join(env.evidenceDir, `${name}.json`)
  await mkdir(env.evidenceDir, { recursive: true }); await writeFile(path, JSON.stringify(value, null, 2) + '\n')
  await info.attach(name, { path, contentType: 'application/json' })
}
export async function screenshots(page: Page, info: TestInfo, name: string) {
  // Projects has no inline skin control. Use the actual Settings control in a
  // second same-origin tab, exercising the real cross-tab preference subscriber
  // without leaving or rebuilding the dirty Project owner.
  const inline = await page.locator('.w2-skin select').count(), themePage = inline ? page : await page.context().newPage()
  const themeWrites = themePage === page ? undefined : observeWrites(themePage)
  try {
    if (themePage !== page) await themePage.goto('/settings')
    for (const skin of ['spdb', 'tech-blue', 'github-white']) {
      await themePage.locator('.w2-skin select').selectOption(skin)
      await page.bringToFront(); await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
      const path = join(env.evidenceDir, `${skin}-${name}.png`)
      await mkdir(env.evidenceDir, { recursive: true }); await page.screenshot({ path, fullPage: true, animations: 'disabled' })
      await info.attach(`${skin}-${name}`, { path, contentType: 'image/png' })
    }
    if (themeWrites) { expect(themeWrites.writes).toEqual([]); expect(themeWrites.errors).toEqual([]) }
  } finally { if (themePage !== page) await themePage.close() }
}
export function observeWrites(page: Page) {
  const writes: { path: string; method: string; body: string | null }[] = []
  const unexpected: string[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/') || ['GET', 'HEAD', 'OPTIONS'].includes(request.method())) return
    // Store only synthetic workflow / project inputs. Multipart original bytes stay in dedicated hash evidence.
    writes.push({ path: url.pathname, method: request.method(), body: request.headers()['content-type']?.includes('application/json') ? request.postData() : null })
    if (/\/messages$|\/control\/(start|pause)$|\/tasks\/[^/]+\/start$|\/(push|writeback|commit)(\/|$)|\/agents-md\/(generate|apply|cancel)$/.test(url.pathname)) unexpected.push(`${request.method()} ${url.pathname}`)
  })
  return { writes, unexpected, errors }
}
