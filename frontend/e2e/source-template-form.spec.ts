import { expect, test } from '@playwright/test'

const sourceInputs = { documents: false, branch: false, dates: false, sourcePath: true }
const templates = [
  { id: 'REQUIREMENT_DEVELOPMENT', title: '需求开发', description: '根据需求文档完成代码开发、测试和验收', category: '需求', icon: 'lucide:code-xml', inputs: { documents: true, branch: false, dates: false } },
  ...['需求代码评审', '历史提交审查', '代码审查', '项目人员贡献周报'].map((title, i) => ({ id: `report-${i}`, title, description: '检查项目代码与交付成果，生成可追溯的报告', category: '审查', inputs: { documents: false, branch: true, dates: true } })),
  { id: 'UNIT_TEST_DEVELOPMENT', title: '单元测试开发', description: '补齐指定源码路径下的单元测试，并运行测试与验收', category: '测试', icon: 'lucide:flask-conical', inputs: { ...sourceInputs, testOutputPath: true } },
  { id: 'DETAILED_DESIGN_WRITING', title: '详细设计编写', description: '根据指定路径的现有代码编写详细设计、流程图和源码覆盖清单', category: '文档', icon: 'lucide:file-text', inputs: { ...sourceInputs, documentOutputPath: true } },
].map(item => ({ ...item, version: '1', stages: [] }))

for (const skin of ['spdb', 'github-white', 'tech-blue']) {
  for (const width of [1440, 390]) {
    test(`${skin} 源码表单 ${width}px 紧凑布局、预检和模板切换`, async ({ page }) => {
      const errors: string[] = [], writes: string[] = []
      page.on('pageerror', error => errors.push(error.message))
      await page.setViewportSize({ width, height: 1100 })
      await page.addInitScript(value => localStorage.setItem('loopper.skin', value), skin)
      await page.route('http://127.0.0.1:41773/api/**', async route => {
        const request = route.request(), path = new URL(request.url()).pathname
        let body: unknown = { items: [], facets: {} }
        if (request.method() === 'POST' && !path.endsWith('/preview')) writes.push(path)
        if (path.endsWith('/catalog')) body = { templates, dimensions: [] }
        else if (path.endsWith('/projects')) body = { items: [{ id: 'p', name: '支付服务', documentPath: 'docs/design', createdAt: 'now' }] }
        else if (path.endsWith('/projects/p')) body = { id: 'p', name: '支付服务', documentPath: 'docs/design', createdAt: 'now' }
        else if (path.endsWith('/preview')) {
          const input = request.postDataJSON()
          body = { sourcePath: input.sourcePath, targetCount: 2, moduleCount: 1, excludedCount: 1, files: [{ path: `${input.sourcePath}/PaymentService.java`, exclusion: null }],
            testProfile: input.templateId === 'UNIT_TEST_DEVELOPMENT' ? { modules: [{ root: '.', framework: 'JUnit', testRoots: ['src/test/java'], fixtureRoots: [] }] } : null,
            documentPath: input.documentPath, configurationProblem: null }
        } else if (path === '/api/runtime/opencode') body = { status: 'OFFLINE', managed: false, checkedAt: 'now' }
        else if (path === '/api/tasks/summaries') body = { tasks: [], facets: {} }
        await route.fulfill({ json: body })
      })
      await page.goto('/template-tasks?projectId=p')
      const form = page.getByRole('form', { name: '模板任务参数' })
      // A short form must not stretch to the height of the seven-item catalog.
      if (width > 1100) {
        const formBounds = await form.boundingBox(), catalogBounds = await page.getByLabel('模板目录', { exact: true }).boundingBox()
        expect(formBounds!.height).toBeLessThan(catalogBounds!.height)
      }
      for (const title of ['单元测试开发', '详细设计编写']) {
        await page.getByRole('button', { name: new RegExp(`^${title}`) }).click()
        await expect(form.getByRole('heading', { name: title, exact: true })).toBeVisible()
        const source = page.getByRole('textbox', { name: '源码路径', exact: true })
        await expect(source).toHaveValue('')
        await expect(page.getByRole('button', { name: '创建任务', exact: true })).toBeDisabled()
        if (title === '单元测试开发') await expect(page.getByRole('textbox', { name: '测试目录', exact: true })).toBeVisible()
        else {
          await expect(page.getByRole('textbox', { name: '测试目录', exact: true })).toHaveCount(0)
          await expect(page.getByRole('textbox', { name: '文档目录', exact: true })).toHaveValue('docs/design')
          await expect(page.getByLabel('任务产出')).toContainText('Markdown')
        }
        const labelBounds = await page.locator('label[for="template-source-path"]').boundingBox(), sourceBounds = await source.boundingBox()
        expect(sourceBounds!.y - labelBounds!.y - labelBounds!.height).toBeLessThan(20)
        await source.fill('src/main/java')
        await page.getByRole('button', { name: '检查处理范围', exact: true }).click()
        await expect(page.getByLabel('处理范围预览', { exact: true })).toContainText('2 个处理文件')
        await expect(page.getByRole('button', { name: '创建任务', exact: true })).toBeEnabled()
        await page.getByText('查看文件与排除原因', { exact: true }).click()
        await expect(page.getByText('src/main/java/PaymentService.java', { exact: true })).toBeVisible()
        await page.getByText('查看文件与排除原因', { exact: true }).click()
        expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth)).toBe(true)
        await page.screenshot({ path: `../data/template-ui-20260924/${skin}-${title}-${width}.png`, fullPage: true })
        await page.getByRole('textbox', { name: '补充要求', exact: true }).fill('优先异常流程')
        await expect(page.getByLabel('处理范围预览', { exact: true })).toHaveCount(0)
        await expect(page.getByRole('button', { name: '创建任务', exact: true })).toBeDisabled()
      }
      expect(errors).toEqual([])
      expect(writes).toEqual([])
    })
  }
}
