import type { DesignerAnsweredQuestion, DesignerMessage } from '@/types/domain'

type HistoryItem =
  | { key: string; kind: 'message'; message: DesignerMessage }
  | { key: string; kind: 'discussion'; entries: DesignerAnsweredQuestion[] }

/** Keep persisted question anchors even when their system snapshot is hidden. */
export function frozenDesignTimeline(messages: DesignerMessage[], questions: DesignerAnsweredQuestion[]): HistoryItem[] {
  const groups = new Map<string, DesignerAnsweredQuestion[]>()
  for (const question of questions) {
    const key = `${question.scope ?? 'REQUIREMENT'}:${question.discussionRevision ?? 0}`
    groups.set(key, [...(groups.get(key) ?? []), question])
  }
  const before = new Map<string, HistoryItem[]>()
  const trailing: HistoryItem[] = []
  const assigned = new Set<string>()
  for (const [key, entries] of groups) {
    const scope = entries[0]?.scope ?? 'REQUIREMENT'
    const linked = entries.find(entry => entry.designMessageId)?.designMessageId
    const target = messages.find(message => message.id === linked)
      ?? messages.find(message => message.actor === 'DESIGNER'
        && (message.workPackageId ?? 'REQUIREMENT') === scope && !assigned.has(message.id))
    const item: HistoryItem = { key: `discussion:${key}`, kind: 'discussion', entries }
    if (target) {
      assigned.add(target.id)
      before.set(target.id, [...(before.get(target.id) ?? []), item])
    } else trailing.push(item)
  }
  const items: HistoryItem[] = []
  const answeredIds = new Set(questions.map(question => question.id))
  for (const message of messages) {
    items.push(...(before.get(message.id) ?? []))
    if (!['USER', 'DESIGNER'].includes(message.actor)) continue
    if (message.deliveryState === 'SERVER_REQUIREMENT_SNAPSHOT') continue
    // A chat fallback question already represented by the answered card is redundant.
    if (message.deliveryState === 'CHAT_QUESTION'
      && (before.has(message.id) || answeredIds.has(`chat-${message.id}`))) continue
    items.push({ key: message.id, kind: 'message', message })
  }
  return [...items, ...trailing]
}
