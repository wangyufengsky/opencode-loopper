import { useEffect, useMemo } from 'react'
import { AttemptFiles, AttemptKnowledge, AttemptPartial, FixedInputContent } from '@/pages/w5/requirements/Content'
import { createContentController, type AttemptContentScope } from '@/pages/w5/requirements/contentController'
import { useRequirementOwner } from '@/pages/w5/requirements/parts'
import { WorkflowCodeChanges as Changes, WorkflowDocumentPreview as Preview } from '@/pages/w5/workflow/reports'
import type { WorkflowInputs } from '@/types/domain'
import { useTestPage } from './react-test-root'

/** Test composition of actual readers/presenters; no stub or alternate business implementation. */
export function WorkflowInputContent({ requirement, node, attempt, input, review }: { requirement: string; node: string; attempt: string; input: WorkflowInputs['values'][number]; review?: boolean }) {
  return <FixedInputContent page={useTestPage()} scope={{ requirement, node, attempt, direction: 'inputs', name: input.name }} input={input} review={review} />
}
export function WorkflowFiles({ archive, changes, ...scope }: AttemptContentScope & { archive?: boolean; changes?: boolean }) { return <AttemptFiles page={useTestPage()} scope={scope} archive={archive} changes={changes} /> }
export function WorkflowKnowledgeEvidence({ requirement, node, attempt }: Pick<AttemptContentScope, 'requirement' | 'node' | 'attempt'>) { return <AttemptKnowledge page={useTestPage()} scope={{ requirement, node, attempt, direction: 'outputs', name: 'evidence' }} /> }
export function WorkflowSnapshotPartialReport({ requirement, node, attempt }: Pick<AttemptContentScope, 'requirement' | 'node' | 'attempt'>) { return <AttemptPartial page={useTestPage()} scope={{ requirement, node, attempt, direction: 'outputs', name: 'analysis' }} /> }
function useReader(scope: AttemptContentScope) {
  const owner = useMemo(() => createContentController(scope), [scope.requirement, scope.node, scope.attempt, scope.direction, scope.name]), page = useTestPage()
  return { owner, page, state: useRequirementOwner(page, owner) }
}
export function WorkflowCodeChanges(scope: AttemptContentScope) {
  const { owner, state: s } = useReader(scope)
  return <Changes rows={s.changes} loaded={s.changesLoaded} busy={s.loading} error={s.error} cursor={s.changeCursor} onLoad={more => { void owner.changes(more) }} fileUrl={owner.fileUrl} />
}
export function WorkflowDocumentPreview({ path, onNavigate, onClose = () => {}, ...scope }: AttemptContentScope & { path: string; onNavigate?(path: string): void; onClose?(): void }) {
  const { owner, page, state: s } = useReader(scope)
  useEffect(() => { void owner.readFile(path) }, [owner, path])
  return <Preview path={path} body={s.body} busy={s.loading} error={s.error} skin={page.skin} onLoad={more => { void owner.readFile(path, more) }} onNavigate={value => onNavigate?.(value)} onClose={onClose} />
}

import { BranchInput } from '@/pages/w5/requirements/Inputs'
export function WorkflowBranchInput(props: { project: string; title: string; value: string; disabled?: boolean; onChange?(value: string): void }) { return <BranchInput {...props} page={useTestPage()} disabled={!!props.disabled} onChange={value => props.onChange?.(value)} /> }
