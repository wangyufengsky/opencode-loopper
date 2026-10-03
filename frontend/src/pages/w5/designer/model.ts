import type { AppSettings, DesignerMessage, DesignerSession, LoopSpec } from '@/types/domain'
import { frozenDesignTimeline } from '@/utils/frozenDesignTimeline'
export const workspaceKey = 'opencode-loopper.designer-workspace'
export const promptKey = 'opencode-loopper.designer-draft-prompt'
export const messageKey = 'opencode-loopper.designer-message-draft'
export function readText(key: string) { try { return sessionStorage.getItem(key) ?? '' } catch { return '' } }
export function storeText(key: string, value: string) { try { if (value) sessionStorage.setItem(key, value); else sessionStorage.removeItem(key) } catch { /* Optional browser persistence does not revoke a receipt. */ } }
export const json = (value: unknown) => JSON.stringify(value, null, 2)
export function blankSpec(projectId: string, goal: string, settings: AppSettings): LoopSpec {
  return { schemaVersion: 'v2', projectId, goal, context: '', stages: [{ objective: '分析目标并实现最小可验证改动', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['可验证实现'], acceptanceCriteria: [], verifiers: [] }], limits: { timeoutEnabled: settings.limits.timeoutEnabled ?? false, maxStageAttempts: settings.limits.maxStageAttempts, maxTaskAttempts: settings.limits.maxTaskAttempts, maxDuration: `PT${settings.limits.maxDurationMinutes}M`, attemptTimeout: `PT${settings.limits.attemptTimeoutMinutes}M` } }
}
export function fileMetadata(files: readonly File[]) { return files.map(file => ({ name: file.name, size: file.size, type: file.type, lastModified: file.lastModified })) }
export function composerAvailable(session: DesignerSession | undefined) {
  if (!session || session.archived || ['STOPPING', 'CANCELLED', 'COMPLETED'].includes(session.state) || session.pendingQuestions?.length) return false
  if (session.questionInteraction.mode === 'CHAT_FALLBACK' && session.questionInteraction.awaitingAnswer) return true
  if (session.autoMode.enabled && session.autoMode.state === 'ACTIVE') return false
  return session.state !== 'RUNNING' && (session.workflowPhase === 'DISCUSSING_REQUIREMENT' || session.workflowPhase === 'REVIEWING_PACKAGE' || session.state === 'WAITING_INPUT')
}
export function directMode(session?: DesignerSession) { return session?.taskProfile.workflowTemplate === 'DIRECT_SOFTWARE_DESIGN' }
export function shouldPoll(session?: DesignerSession) { return !!session && !session.archived && (['RUNNING', 'STOPPING'].includes(session.state) || session.taskProfile.decisionState === 'ROUTING') }
export function mutationGap(session?: DesignerSession) { const item = session?.workPackages?.find(pkg => pkg.id === session.activeWorkPackageId); return session?.state === 'WAITING_INPUT' && item?.state === 'WAITING_INPUT' && ((item.acceptancePlanning?.unresolvedMutationObligationCount ?? 0) > 0 || item.acceptancePlanning?.pathConservation === 'BLOCKED') }
export type TimelineEntry = { kind: 'message'; key: string; message: DesignerMessage } | { kind: 'discussion'; key: string; entries: NonNullable<DesignerSession['answeredQuestions']> } | { kind: 'system'; key: string; messages: DesignerMessage[] } | { kind: 'validators'; key: string; messages: DesignerMessage[] }
/** Frozen question anchors, augmented with the original full actor history; raw stream output never enters this list. */
export function timeline(session: DesignerSession, selected = ''): TimelineEntry[] {
  const messages = session.messages.filter(m => (!selected || !m.workPackageId || m.workPackageId === selected) && !m.content.includes('LOOPSPEC_COMPILATION_JSON_START') && !(m.role === 'SYSTEM' && m.deliveryState === 'PENDING_HANDOFF' && !m.content.startsWith('SYSTEM_ERROR')))
  const questions = (session.answeredQuestions ?? []).filter(question => !selected || !question.scope || question.scope === 'REQUIREMENT' || question.scope === selected)
  const groups = frozenDesignTimeline(messages, questions).filter(entry => entry.kind === 'discussion')
  const before = new Map<string, TimelineEntry[]>(), trailing: TimelineEntry[] = [], assigned = new Set<string>()
  for (const group of groups) {
    const linked = group.entries.find(question => question.designMessageId)?.designMessageId
    const scope = group.entries[0]?.scope || 'REQUIREMENT'
    const target = messages.find(message => message.id === linked) ?? messages.find(message => message.actor === 'DESIGNER' && (message.workPackageId || 'REQUIREMENT') === scope && !assigned.has(message.id))
    if (target) { assigned.add(target.id); before.set(target.id, [...(before.get(target.id) ?? []), group]) } else trailing.push(group)
  }
  const result: TimelineEntry[] = []; let validators = false
  for (const message of messages) {
    result.push(...(before.get(message.id) ?? []))
    if (message.deliveryState === 'SERVER_REQUIREMENT_SNAPSHOT') continue
    if (message.actor === 'VALIDATOR') { if (!validators) { result.push({ kind: 'validators', key: 'validators', messages: messages.filter(m => m.actor === 'VALIDATOR') }); validators = true } continue }
    const previous = result.at(-1)
    if (message.actor === 'SYSTEM') { if (previous?.kind === 'system') previous.messages.push(message); else result.push({ kind: 'system', key: `system:${message.id}`, messages: [message] }) }
    else result.push({ kind: 'message', key: message.id, message })
  }
  result.push(...trailing); return result
}
