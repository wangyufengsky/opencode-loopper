import { afterEach, beforeEach, it, vi } from 'vitest'
import { api } from '@/api/client'
import { templateSourcesRetirementW0Contract, templateClarificationRetirementW0Contract, templateSupplementRetirementW0Contract, templateBatchW0Contract, templateDiagnosticW0Contract } from './run-child-w0-contract'
beforeEach(() => {
  vi.useFakeTimers()
  for (const key of ['documentSections', 'documentSection', 'answerDocumentRequirements', 'documentSupplementOptions', 'uploadDocumentSupplement', 'templateFailedBatches', 'retrySelectedTemplateBatches', 'getTemplateSessionDiagnostics', 'recoverTemplateSession'] as const) vi.spyOn(api, key)
})
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
for (const kind of ['directory', 'body'] as const) it(`B8.3 real React ${kind} root retirement protects original complete snapshot`, async () => { await templateSourcesRetirementW0Contract(kind) })
it('B8.3 real React clarification disabled sending field survives forced root retirement and next draft', async () => { await templateClarificationRetirementW0Contract() })
for (const kind of ['options', 'upload'] as const) it(`B8.3 real React supplement ${kind} root retirement preserves input and refuses late updated callback`, async () => { await templateSupplementRetirementW0Contract(kind) })
for (const mode of ['original-cas', 'no-auto-write', 'stop-proof'] as const) it(`B9 real React batch ${mode} retains original transport identity`, async () => { await templateBatchW0Contract(mode) })
for (const mode of ['accepted-read-failure', 'unknown-identity'] as const) it(`B9 real React diagnostics ${mode} rejects another write owner`, async () => { await templateDiagnosticW0Contract(mode) })
