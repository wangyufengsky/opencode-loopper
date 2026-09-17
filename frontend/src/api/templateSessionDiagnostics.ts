import type { TemplateSessionDiagnostic, TemplateSessionDiagnosticPage } from '@/types/domain'

export function normalizeTemplateDiagnostic(value: unknown): TemplateSessionDiagnostic {
  const raw = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  const str = (key: string) => typeof raw[key] === 'string' ? raw[key] as string : null
  const num = (key: string) => typeof raw[key] === 'number' && Number.isSafeInteger(raw[key]) && (raw[key] as number) >= 0 ? raw[key] as number : 0
  const validIdentity = Boolean(str('batchId')) && typeof raw.batchVersion === 'number' && Number.isSafeInteger(raw.batchVersion) && raw.batchVersion >= 0
  return {
    batchId: str('batchId') ?? '', batchVersion: num('batchVersion'), sessionKey: str('sessionKey'),
    localSessionId: str('localSessionId'), externalSessionId: str('externalSessionId'),
    purpose: str('purpose') ?? '', ordinal: num('ordinal'), generation: num('generation'), stageOrdinal: num('stageOrdinal'),
    state: str('state') ?? '', phase: str('phase') ?? '', reason: str('reason') ?? '正在核对批次状态',
    acceptedAt: str('acceptedAt'), observedAt: str('observedAt'), lastActivityAt: str('lastActivityAt'), lastProgressAt: str('lastProgressAt'),
    remoteState: str('remoteState'), connected: raw.connected === true, stopProof: str('stopProof'), stopConfirmedAt: str('stopConfirmedAt'),
    canFinalize: validIdentity && raw.canFinalize === true, canStop: validIdentity && raw.canStop === true,
    worktreePath: str('worktreePath'), requestMessageId: str('requestMessageId'), submissionRevision: num('submissionRevision'),
    candidateAccepted: raw.candidateAccepted === true, recoveryRequestedAt: str('recoveryRequestedAt'), recoveryAction: str('recoveryAction'),
    automaticRetries: num('automaticRetries'), retryLimit: num('retryLimit'), nextRetryAt: str('nextRetryAt'),
    failedOperation: str('failedOperation'), transportError: str('transportError'), transportMessage: str('transportMessage'),
    firstFailedAt: str('firstFailedAt'), lastFailedAt: str('lastFailedAt'), transportFailures: num('transportFailures'),
    nextCheckAt: str('nextCheckAt'), canCheck: validIdentity && raw.canCheck === true,
  }
}

export function normalizeTemplateDiagnosticPage(value: unknown): TemplateSessionDiagnosticPage {
  const raw = value && typeof value === 'object' ? value as Record<string, unknown> : {}
  const nextCursor = typeof raw.nextCursor === 'string' && raw.nextCursor ? raw.nextCursor : null
  return { items: Array.isArray(raw.items) ? raw.items.map(normalizeTemplateDiagnostic) : [], nextCursor,
    hasMore: Boolean(nextCursor) && raw.hasMore !== false }
}
