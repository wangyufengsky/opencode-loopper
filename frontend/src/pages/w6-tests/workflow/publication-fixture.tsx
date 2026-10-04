import { PublicationPanel } from '@/pages/w5/requirements/Publication'
import { createPublicationController, type ArtifactKind } from '@/pages/w5/requirements/publicationController'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview } from '@/types/domain'
import { useTestPage, mount, flushPromises } from './react-test-root'
export type PublicationOwner = ReturnType<typeof createPublicationController>
function Panel({ owner }: { owner: PublicationOwner }) { return <PublicationPanel page={useTestPage()} controller={owner} revision={owner.getSnapshot().preview?.planRevision ?? 2} visible onClose={() => owner.close()} /> }
/** Known parent projection is fixture data. Every read/write/cancel/guard remains production code. */
export async function publicationFixture(kind?: ArtifactKind, preview?: WorkflowPublicationPreview | null, checked?: WorkflowWritebackPreview | null, requirement = 'req') {
  const owner = createPublicationController(requirement, preview?.planRevision ?? 2)
  if (preview || checked) owner.patch({ preview: preview ?? null, checked: checked ?? null })
  const view = mount(Panel, { props: { owner } }); await flushPromises()
  if (kind) await owner.readStatus(kind)
  await flushPromises()
  return { owner, view, dispose: () => view.unmount() }
}
