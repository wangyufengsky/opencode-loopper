import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { webcrypto } from 'node:crypto'
import { pptApi } from '@/api/ppt'
import { pptTitleFromPrompt, usePptCreation } from './usePptCreation'
import { pptDocument } from './pptTestFixtures'

vi.mock('@/api/ppt', () => ({
  pptApi: {
    create: vi.fn(),
    get: vi.fn(),
    upload: vi.fn(),
  },
}))
const api = vi.mocked(pptApi)
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.stubGlobal('crypto', webcrypto)
  api.create.mockResolvedValue(pptDocument())
  api.get.mockResolvedValue(pptDocument())
})

describe('PPT initial request recovery', () => {
  it('retains the exact generation identity across reload even when the server revision advances', async () => {
    const first = effectScope()
    const draft = first.run(usePptCreation)!
    draft.prompt.value = '面向管理层的季度经营汇报'
    draft.project.value = { id: 'project-1', name: '支付平台' }
    const initial = await draft.prepare()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        title: draft.prompt.value,
        projectId: 'project-1',
      }),
    )
    first.stop()
    api.get.mockResolvedValue({
      ...pptDocument(),
      revision: 20,
    })
    const second = effectScope()
    const recovered = second.run(usePptCreation)!
    expect(recovered.locked.value).toBe(true)
    expect(recovered.project.value).toEqual({ id: 'project-1', name: '支付平台' })
    expect(await recovered.prepare()).toEqual(initial)
    expect(api.create).toHaveBeenCalledTimes(1)
    recovered.accepted()
    await nextTick()
    expect(recovered.locked.value).toBe(false)
    expect(recovered.project.value).toBeNull()
    second.stop()
  })
  it('allows an independent presentation without any project lookup or association', async () => {
    const scope = effectScope()
    const draft = scope.run(usePptCreation)!
    draft.prompt.value = '做一份读书分享'
    expect(await draft.prepare()).not.toBeNull()
    expect(api.create.mock.calls[0]?.[0]).not.toHaveProperty('projectId')
    scope.stop()
  })
  it('requires original file reselection after reload and replays an uncertain upload with its original identity', async () => {
    const file = new File(['# 季度成果'], '材料.md', {
      type: 'text/markdown',
      lastModified: 100,
    })
    const first = effectScope()
    const draft = first.run(usePptCreation)!
    draft.prompt.value = '依据材料制作汇报'
    draft.addFiles([file])
    api.upload.mockRejectedValueOnce(new Error('network'))
    expect(await draft.prepare()).toBeNull()
    const original = api.upload.mock.calls[0]
    first.stop()
    const second = effectScope()
    const recovered = second.run(usePptCreation)!
    expect(await recovered.prepare()).toBeNull()
    expect(recovered.error.value).toContain('重新选择')
    recovered.addFiles([file])
    api.upload.mockResolvedValue({
      state: 'READY',
    } as never)
    expect(await recovered.prepare()).not.toBeNull()
    expect(api.upload.mock.calls[1]).toEqual(original)
    expect(api.create).toHaveBeenCalledTimes(1)
    second.stop()
  })
})

describe('PPT concise naming', () => {
  it('uses an explicitly named topic without shortening the actual requirement', () => {
    const prompt =
      '制作一份6页中文季度项目汇报，面向部门领导，5分钟讲完。主题是“服务质量提升”，简洁商务风。'
    expect(pptTitleFromPrompt(prompt)).toBe('服务质量提升')
    expect(pptTitleFromPrompt('主题为：2026年度工作计划。12页')).toBe('2026年度工作计划')
  })
  it('falls back to the first sentence and limits the title to 24 characters', () => {
    expect(pptTitleFromPrompt('汇报季度成果。包含三项指标')).toBe('汇报季度成果')
    expect(
      Array.from(
        pptTitleFromPrompt(
          '做一份面向全公司管理层汇报年度数字化转型项目进展与下一年度重点工作安排的演示文稿',
        ),
      ),
    ).toHaveLength(24)
  })
})
