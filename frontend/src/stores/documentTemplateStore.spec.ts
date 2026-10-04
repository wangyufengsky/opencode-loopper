import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { api, ApiError } from '@/api/client'
import { createDocumentCreationController, documentUploadError } from '@/pages/w3/templates/catalog/creation'
import { documentRun } from '@/pages/w3/templates/catalog/fixtures'
const input = { templateId: 'REQUIREMENT_DEVELOPMENT', templateVersion: '1', projectId: 'project' }
function file(content: string) { const value = new File([content], '需求.md', { type: 'text/markdown' }); Object.defineProperty(value, 'arrayBuffer', { value: async () => new TextEncoder().encode(content).buffer }); return value }
const owners: ReturnType<typeof createDocumentCreationController>[] = []
function owner() { const value=createDocumentCreationController(); owners.push(value); return value }
beforeEach(() => { sessionStorage.clear(); vi.stubGlobal('crypto',webcrypto); vi.spyOn(api,'documentTemplateRequest').mockRejectedValue(new ApiError('未找到',404)); vi.spyOn(api,'documentTemplate').mockImplementation(async id=>documentRun(id)) })
afterEach(()=>{owners.forEach(value=>value.retire(true)); owners.length=0;vi.restoreAllMocks();vi.unstubAllGlobals()})
describe('document template upload identity',()=>{
 it('reuses a lost-response request after a page reload and binds it to actual file bytes',async()=>{const create=vi.spyOn(api,'createDocumentTemplate').mockRejectedValueOnce(new Error('connection lost')).mockResolvedValue(documentRun('frozen-run')); const first=owner();expect(await first.start(input,[file('必须鉴权')])).toBeUndefined();expect(first.getSnapshot().phase).toBe('UNKNOWN');const original=create.mock.calls[0]![0]; first.retire(true); const restored=owner();expect(await restored.start(input,[file('必须鉴权')])).toBe('frozen-run');expect(create.mock.calls[1]![0].requestKey).toBe(original.requestKey);restored.completeHandoff();restored.retire(true);expect(await owner().start(input,[file('必须鉴权')])).toBe('frozen-run');expect(create.mock.calls[2]![0].requestKey).not.toBe(original.requestKey)})
 it('rejects unsupported and oversized batches before any network call',async()=>{const create=vi.spyOn(api,'createDocumentTemplate'),value=owner();expect(await value.start(input,[new File(['x'],'旧版.doc')])).toBeUndefined();expect(value.getSnapshot().error).toContain('支持 DOCX');expect(documentUploadError(Array.from({length:11},()=>file('x')))).toContain('1–10');const large=file('x');Object.defineProperty(large,'size',{value:20*1024*1024+1});expect(documentUploadError([large])).toContain('20 MiB');expect(create).not.toHaveBeenCalled()})
 it('blocks a duplicate click while hashing and uploading',async()=>{let resolve!:(value:ReturnType<typeof documentRun>)=>void;const create=vi.spyOn(api,'createDocumentTemplate').mockReturnValue(new Promise(done=>{resolve=done}));const value=owner(),first=value.start(input,[file('分页')]);expect(await value.start(input,[file('分页')])).toBeUndefined();await vi.waitFor(()=>expect(create).toHaveBeenCalledTimes(1));resolve(documentRun('only-run'));expect(await first).toBe('only-run')})
})
