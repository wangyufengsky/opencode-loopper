import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { webcrypto } from 'node:crypto'
import { api } from '@/api/client'
import { documentUploadError, useDocumentTemplateStore } from './documentTemplateStore'
vi.mock('@/api/client', () => ({ api: { createDocumentTemplate: vi.fn(), documentTemplateRequest: vi.fn() }, ApiError: class extends Error { status = 404 } }))
const input = { templateId: 'REQUIREMENT_DEVELOPMENT', templateVersion: '1', projectId: 'project' }
function file(content: string) {
  const value = new File([content], '需求.md', { type: 'text/markdown' })
  Object.defineProperty(value, 'arrayBuffer', { value: async () => new TextEncoder().encode(content).buffer })
  return value
}
describe('document template upload identity', () => {
  beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); setActivePinia(createPinia()); vi.stubGlobal('crypto', webcrypto) })
  it('reuses a lost-response request after a page reload and binds it to actual file bytes', async () => {
    vi.mocked(api.createDocumentTemplate).mockRejectedValueOnce(new Error('connection lost'))
    await expect(useDocumentTemplateStore().start(input, [file('必须鉴权')])).rejects.toThrow()
    const original = vi.mocked(api.createDocumentTemplate).mock.calls[0]![0]
    setActivePinia(createPinia())
    vi.mocked(api.createDocumentTemplate).mockResolvedValue({ id: 'frozen-run' } as never)
    expect(await useDocumentTemplateStore().start(input, [file('必须鉴权')])).toBe('frozen-run')
    expect(vi.mocked(api.createDocumentTemplate).mock.calls[1]![0].requestKey).toBe(original.requestKey)
    await useDocumentTemplateStore().start(input, [file('必须鉴权')])
    expect(vi.mocked(api.createDocumentTemplate).mock.calls[2]![0].requestKey).not.toBe(original.requestKey)
  })
  it('rejects unsupported and oversized batches before any network call', async () => {
    const store = useDocumentTemplateStore()
    await expect(store.start(input, [new File(['x'], '旧版.doc')])).rejects.toThrow('支持 DOCX')
    expect(documentUploadError(Array.from({ length: 11 }, () => file('x')))).toContain('1–10')
    const large = file('x'); Object.defineProperty(large, 'size', { value: 20 * 1024 * 1024 + 1 })
    expect(documentUploadError([large])).toContain('20 MiB')
    expect(api.createDocumentTemplate).not.toHaveBeenCalled()
  })
  it('blocks a duplicate click while hashing and uploading', async () => {
    let resolve!: (value: never) => void
    vi.mocked(api.createDocumentTemplate).mockReturnValue(new Promise(done => { resolve = done }))
    const store = useDocumentTemplateStore(); const first = store.start(input, [file('分页')])
    expect(await store.start(input, [file('分页')])).toBeUndefined()
    await vi.waitFor(() => expect(api.createDocumentTemplate).toHaveBeenCalledTimes(1))
    resolve({ id: 'only-run' } as never)
    expect(await first).toBe('only-run')
  })
})
