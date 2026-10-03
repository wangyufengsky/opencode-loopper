import { vi } from 'vitest'
import { act } from '@testing-library/react'
import { api, subscribeDesignerEvents } from '@/api/client'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import type { AppSettings, DesignerSession, DesignerStreamEvent, LoopDraft, LoopSpec, Project } from '@/types/domain'
export { pageProps } from '@/pages/w3/ppt/testFixture'
export function spec(goal = '已保存草稿'): LoopSpec { return { schemaVersion: 'v2', projectId: 'project', goal, context: '', stages: [{ objective: '聚焦验证', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['测试证据'], acceptanceCriteria: [], verifiers: [] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } } }
export function draft(value = spec()): LoopDraft { return { id: 'draft-A', version: 0, status: 'DRAFT_READY', updatedAt: '2026-10-03', spec: value } }
export function session(id = 'A', overrides: Partial<DesignerSession> = {}): DesignerSession { return { id, projectId: 'project', projectName: '测试项目', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT', activeActor: 'SYSTEM', accessMode: 'READ_ONLY', readOnly: true, discussionScope: 'REQUIREMENT', discussionRevision: 1, finalConfirmationEligible: false, autoMode: { enabled: false, state: 'DISABLED', version: 0 }, questionInteraction: { mode: 'NONE', awaitingAnswer: false }, taskProfile: { id: `profile-${id}`, state: 'PROVISIONAL', decisionState: 'CONFIRMED', confirmationReady: true, intent: 'SOFTWARE_CHANGE', workflowTemplate: 'FULL_PACKAGE_DESIGN', mutationMode: 'WRITE_CODE', artifactKinds: ['SOURCE_CODE'], technologies: [], testPolicy: 'REQUIRED', executionStrategy: 'OPEN_CODE_IMPLEMENTATION', rolePackId: 'fixture', rolePackVersion: 'fixture', confidence: 100, evidence: [], resolutionSource: 'USER_CONFIRMED', decisionRequired: false, largeTaskMode: false, version: 7 }, availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'], availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'], reports: [], messages: [], draft: draft(), ...overrides } }
export const project: Project = { id: 'project', name: '测试项目', rootPath: '/fixture', status: 'READY', updatedAt: '2026-10-03', taskCount: 0, openDesignerSessionCount: 0 }
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
export async function flush() { await act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }) }
export const frame = (child: React.ReactNode) => <FoundationProvider skin={skins[0]!}>{child}</FoundationProvider>
export function mockDesigner(value = session()) {
  const streams: { id: string; event(event: DesignerStreamEvent): void; close: ReturnType<typeof vi.fn> }[] = []
  vi.mocked(subscribeDesignerEvents).mockImplementation((id, event) => { const stream = { id, event, close: vi.fn() }; streams.push(stream); return stream })
  vi.spyOn(api, 'getDesignerSession').mockResolvedValue(value); vi.spyOn(api, 'getDesignerMessages').mockResolvedValue(value.messages)
  vi.spyOn(api, 'getStoryBindingCapability').mockResolvedValue({ available: false, state: 'UNAVAILABLE', reason: '测试未连接外部系统', checkedAt: '2026-10-03' }); vi.spyOn(api, 'getProjects').mockResolvedValue([project]); vi.spyOn(api, 'getSettingsModels').mockResolvedValue([])
  vi.spyOn(api, 'getSettings').mockResolvedValue({ limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDurationMinutes: 120, attemptTimeoutMinutes: 30 }, openCode: { provider: 'fixture', model: 'fixture' } } as AppSettings)
  vi.spyOn(api, 'getDraft').mockResolvedValue(draft()); vi.spyOn(api, 'createDraft').mockResolvedValue(draft()); vi.spyOn(api, 'validateDraft').mockResolvedValue({ valid: true, schemaVersion: 'v2', legacy: false, errors: [], stageAssessments: [] })
  vi.spyOn(api, 'createDesignerContextTurn').mockResolvedValue(value); vi.spyOn(api, 'createDesignerSession').mockResolvedValue(value)
  vi.spyOn(api, 'sendDesignerContextTurn').mockResolvedValue({ sessionId: value.id, state: value.state, persistedMessages: [], notice: '' }); vi.spyOn(api, 'sendRequirementMessage').mockResolvedValue({ sessionId: value.id, state: value.state, persistedMessages: [], notice: '' })
  return { streams }
}
