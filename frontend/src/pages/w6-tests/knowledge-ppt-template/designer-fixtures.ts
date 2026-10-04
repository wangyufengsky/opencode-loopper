import type {AppSettings,DesignerSession,LoopDraft,LoopSpec,Project,TaskProfileRouterRun} from '@/types/domain'
export const project: Project = {
  id: 'project-1',
  name: 'Loopper',
  rootPath: '/tmp/loopper',
  status: 'READY',
  updatedAt: 'now',
  taskCount: 0,
  openDesignerSessionCount: 0,
}

export const session: DesignerSession = {
  id: 'designer-1',
  projectId: project.id,
  projectName: project.name,
  state: 'COMPLETED',
  workflowPhase: 'COMPLETED',
  activeActor: 'SYSTEM',
  accessMode: 'READ_ONLY',
  readOnly: true,
  discussionScope: 'FINAL',
  discussionRevision: 1,
  finalConfirmationEligible: true,
  autoMode: { enabled: false, state: 'DISABLED', version: 0 },
  questionInteraction: { mode: 'NONE', awaitingAnswer: false },
  taskProfile: {
    state: 'FROZEN', decisionState: 'FROZEN', confirmationReady: true,
    intent: 'SOFTWARE_CHANGE', workflowTemplate: 'FULL_PACKAGE_DESIGN',
    mutationMode: 'WRITE_CODE', artifactKinds: ['SOURCE_CODE'], technologies: ['java'],
    testPolicy: 'REQUIRED', executionStrategy: 'OPEN_CODE_IMPLEMENTATION',
    rolePackId: 'software-java', rolePackVersion: 'test', confidence: 100,
    evidence: [], resolutionSource: 'TEST', decisionRequired: false, largeTaskMode: true, version: 0,
  },
  availableProfileOverrides: [],
  availableArtifactOverrides: [],
  reports: [],
  messages: [],
}

export const settings: AppSettings = {
  runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '/tmp', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
  openCode: { cliPath: 'opencode', mode: 'auto', baseUrl: 'http://127.0.0.1:4096', provider: 'openai', model: 'gpt-5', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
  limits: { maxStageAttempts: 3, maxTaskAttempts: 7, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 45, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
  retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 },
  publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 },
  appliedLiveFields: [], restartRequiredFields: [],
}

export function draftFrom(spec: LoopSpec): LoopDraft {
  return { id: 'draft-1', version: 0, status: 'DRAFT_READY', updatedAt: 'now', spec }
}

export function completedRouterRun(id = 'router-1'): TaskProfileRouterRun {
  return {
    id, state: 'COMPLETED', externalState: 'COMPLETED', createdAt: '2026-08-25T07:00:00Z',
    updatedAt: '2026-08-25T07:00:03Z', deadlineAt: '2026-08-25T07:04:00Z', retryAvailable: true,
  }
}
