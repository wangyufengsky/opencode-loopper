import { expect, test, type Page } from '@playwright/test'
import { skins, SKIN_STORAGE_KEY } from '../src/themes/registry'

const slot = 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY'
const role = {
  roleId: 'builtin.package-designer', displayName: '工作包设计师', description: '整理工作包设计', origin: 'BUILTIN',
  latestRevisionId: 'revision-2', latestRevisionNumber: 2, activeSlots: [slot],
  groupKey: 'package-designer', groupLabel: '工作包设计师',
}
const sourceSha256 = 'b'.repeat(64)
const revision = (revisionId: string) => ({
  roleId: role.roleId, revisionId, revisionNumber: revisionId === 'revision-2' ? 2 : 1,
  contentSha256: (revisionId === 'revision-2' ? 'a' : 'c').repeat(64), publishedAt: '2026-09-24T00:00:00Z',
  manifest: { roleId: role.roleId, allowedSlots: [slot], runtimePolicy: 'WORKFLOW_ADAPTER' },
  promptFragments: { 'machine-role.package-designer': revisionId === 'revision-2' ? '请完成当前工作包设计。' : '请整理旧版工作包设计。' },
  promptVariables: [], permissionMode: 'BASELINE', modelPolicy: 'INHERIT_WORKFLOW',
  nativeTools: ['read'], mcpTools: ['mcp__loopper__read_package'], requiredMcpTools: ['mcp__loopper__read_package'],
})

type Scenario = { empty?: boolean; listFailsOnce?: boolean; validationFailsOnce?: boolean; publishConflictsOnce?: boolean }

async function mockRolesApi(page: Page, scenario: Scenario = {}) {
  const calls = { list: 0, validate: 0, publish: 0, publishedRequests: [] as string[] }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (path === '/api/roles') {
      calls.list++
      if (scenario.listFailsOnce && calls.list === 1) return json({ message: 'unavailable' }, 503)
      return json({ items: scenario.empty ? [] : [role], nextCursor: null })
    }
    if (path === `/api/roles/${role.roleId}`) return json(role)
    if (path === '/api/role-bindings') return json([{ slot, profile: slot, activeRoleId: role.roleId,
      activeRevisionId: role.latestRevisionId, bindingVersion: scenario.publishConflictsOnce && calls.publish ? 4 : 3,
      label: '工作包设计 · 候选', purpose: '制作工作包设计候选' }])
    if (path === `/api/roles/${role.roleId}/revisions`) return json({ items: [
      { revisionId: 'revision-2', revisionNumber: 2, contentSha256: 'a'.repeat(64), publishedAt: '2026-09-24T00:00:00Z' },
      { revisionId: 'revision-1', revisionNumber: 1, contentSha256: 'c'.repeat(64), publishedAt: '2026-09-23T00:00:00Z' },
    ], nextCursor: null })
    if (path === `/api/roles/${role.roleId}/revisions/revision-2`) return json(revision('revision-2'))
    if (path === `/api/roles/${role.roleId}/revisions/revision-1`) return json(revision('revision-1'))
    if (path === `/api/roles/${role.roleId}/compare`) return json({ fromRevisionId: 'revision-1', toRevisionId: 'revision-2',
      changes: [{ path: '/prompts/machine-role.package-designer', before: '请整理旧版工作包设计。', after: '请完成当前工作包设计。' }] })
    if (path === `/api/roles/${role.roleId}/preview`) return json({ scope: 'CONFIG_ONLY', slot, complete: false,
      limitations: ['工具目录及项目授权要在会话创建时核定。'],
      rules: [{ permission: 'read', pattern: '*', action: 'allow' }],
      mcpTools: [
        { name: 'mcp__loopper__read_package', source: 'ROLE_DECLARATION', required: true, available: false },
        { name: 'mcp__loopper__submit_candidate', source: 'SYSTEM_REQUIRED', required: true, available: false },
      ] })
    if (path === '/api/role-imports/validate') {
      calls.validate++
      if (scenario.validationFailsOnce && calls.validate === 1) return json({ message: 'unavailable' }, 503)
      return json({ sourceSha256, valid: true,
        roles: [{ roleId: role.roleId, displayName: role.displayName, change: 'UPDATED' }],
        activations: [{ slot, roleId: role.roleId, expectedVersion: scenario.publishConflictsOnce && calls.publish > 0 ? 4 : 3 }],
        diagnostics: [], changes: [{ path: '/prompts/machine-role.package-designer', before: '旧提示', after: '新提示' }] })
    }
    if (path === '/api/role-imports/publish') {
      calls.publish++
      calls.publishedRequests.push(route.request().postData() ?? '')
      if (scenario.publishConflictsOnce && calls.publish === 1) return json({ message: 'conflict' }, 409)
      return json({ sourceSha256, roles: [], bindings: [], replayed: false })
    }
    if (path === '/api/projects/summaries') return json([])
    if (path === '/api/runtime/opencode') return json({ status: 'OFFLINE', managed: false, checkedAt: '2026-09-24T00:00:00Z' })
    return json({})
  })
  return calls
}

async function selectRole(page: Page) {
  await expect(page.locator('.role-item')).toBeVisible()
  await page.locator('.role-item').click()
  await expect(page.getByRole('heading', { name: '用途与阶段' })).toBeVisible()
}

async function chooseZip(page: Page) {
  await page.getByLabel('选择角色配置 ZIP').setInputFiles({
    name: 'roles.zip', mimeType: 'application/zip', buffer: Buffer.from('zip bytes'),
  })
}

test('深层路由、静态模板、历史版本与差异在重载后可重新查看', async ({ page }) => {
  await mockRolesApi(page)
  await page.goto('/settings/roles')
  await expect(page.getByRole('heading', { name: '角色管理' })).toBeVisible()
  await selectRole(page)
  await page.getByRole('button', { name: 'Prompt 模板' }).click()
  await page.locator('.detail-section .fragment summary').click()
  await expect(page.getByText('请完成当前工作包设计。')).toBeVisible()
  await expect(page.getByText('无显式模板变量。')).toBeVisible()
  await page.getByRole('button', { name: '版本历史' }).click()
  await page.locator('.history-list li').nth(1).getByRole('button', { name: '查看此版本' }).click()
  await page.locator('.history-revision .fragment summary').click()
  await expect(page.getByText('请整理旧版工作包设计。')).toBeVisible()
  await page.locator('.history-list li').nth(1).getByRole('button', { name: '与最新发布版本比较' }).click()
  await expect(page.getByRole('heading', { name: '与最新发布版本的差异' })).toBeVisible()
  await expect(page.locator('.diff-panel')).toContainText('请完成当前工作包设计。')
  await page.reload()
  await expect(page.getByRole('heading', { name: '角色管理' })).toBeVisible()
  await selectRole(page)
})

test('导入失败保留 ZIP，绑定冲突后重新校验并使用新的预期版本发布', async ({ page }) => {
  const calls = await mockRolesApi(page, { validationFailsOnce: true, publishConflictsOnce: true })
  await page.goto('/settings/roles')
  await chooseZip(page)
  await expect(page.getByText('已选文件：roles.zip')).toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: '配置包校验失败' })).toBeVisible()
  await page.getByRole('button', { name: '重新校验' }).click()
  await expect(page.locator('.diff-panel')).toContainText('新提示')
  await expect(page.getByRole('button', { name: '发布并激活' })).toBeDisabled()
  await page.getByRole('checkbox', { name: /我已核对角色/ }).check()
  await page.getByRole('button', { name: '发布并激活' }).click()
  await expect(page.getByText('角色配置或绑定版本已变化')).toBeVisible()
  await expect(page.getByText('已选文件：roles.zip')).toBeVisible()
  await expect(page.getByRole('button', { name: '发布并激活' })).toHaveCount(0)
  await page.getByRole('button', { name: '重新校验' }).click()
  await expect(page.locator('.diff-panel')).toContainText('新提示')
  await page.getByRole('checkbox', { name: /我已核对角色/ }).check()
  await page.getByRole('button', { name: '发布并激活' }).click()
  await expect(page.getByText('配置包已发布并激活')).toBeVisible()
  expect(calls.validate).toBe(3)
  expect(calls.publish).toBe(2)
  expect(calls.publishedRequests[0]).toContain('"expectedVersion":3')
  expect(calls.publishedRequests[1]).toContain('"expectedVersion":4')
})

test('角色列表无网络时可重试，空结果与未选角色有明确状态', async ({ page }) => {
  const calls = await mockRolesApi(page, { listFailsOnce: true, empty: true })
  await page.goto('/settings/roles')
  await expect(page.locator('.role-list .error-text')).toBeVisible()
  await page.locator('.role-list .error-text').getByRole('button', { name: '重试' }).click()
  await expect(page.getByText('没有匹配的角色。')).toBeVisible()
  await expect(page.getByText('选择一个角色查看配置')).toBeVisible()
  expect(calls.list).toBe(2)
})

for (const skin of skins.map(item => item.id)) {
  for (const width of [1440, 390]) {
    test(`${skin} ${width}px 角色权限预估可读且布局不重叠`, async ({ page }, testInfo) => {
      await page.addInitScript(({ key, value }) => localStorage.setItem(key, value), { key: SKIN_STORAGE_KEY, value: skin })
      await page.setViewportSize({ width, height: 900 })
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await mockRolesApi(page)
      await page.goto('/settings/roles')
      await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
      await selectRole(page)
      await page.getByRole('button', { name: '权限与 MCP' }).click()
      await expect(page.locator('.preview-status')).toContainText('仍需运行时核定')
      await expect(page.locator('.limitations')).toContainText('工具目录及项目授权要在会话创建时核定。')
      await expect(page.locator('.detail-section .tool-list').last()).toContainText('mcp__loopper__read_package')
      await expect(page.locator('.detail-section .tool-list').last()).toContainText('待会话发现')
      await expect(page.locator('.detail-section .tool-list').last()).toContainText('服务端必需')

      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
      expect(overflow).toBe(false)
      const list = await page.locator('.role-list').boundingBox()
      const detail = await page.locator('.role-detail').boundingBox()
      expect(list).not.toBeNull()
      expect(detail).not.toBeNull()
      if (width >= 900) expect(list!.x + list!.width).toBeLessThanOrEqual(detail!.x + 1)
      else expect(list!.y + list!.height).toBeLessThanOrEqual(detail!.y + 1)

      const screenshot = testInfo.outputPath(`roles-${skin}-${width}.png`)
      await page.screenshot({ path: screenshot, fullPage: true })
      await testInfo.attach('角色页视觉证据', { path: screenshot, contentType: 'image/png' })
    })
  }
}
