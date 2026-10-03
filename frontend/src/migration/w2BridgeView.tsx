import { createElement, useSyncExternalStore, type ComponentType } from 'react'
import { FoundationProvider } from '@/foundation/provider'
import { UiConfirmDialog } from '@/foundation/components'
import type { SnapshotPort } from '@/foundation/contracts/types'
import type { W2PageProps } from '@/pages/w2/shared/types'

export interface BridgeDialogSnapshot {
  open: boolean
  reason: string
  blocked: boolean
  notice: string
}
export interface BridgeDialogPort extends SnapshotPort<BridgeDialogSnapshot> { choose(allow: boolean): void }
function BridgeDialog({ port }: { port: BridgeDialogPort }) {
  const state = useSyncExternalStore(port.subscribe, port.getSnapshot, port.getSnapshot)
  return <>
    {state.notice && <div className="w2-status" role="alert">{state.notice}</div>}
    <UiConfirmDialog open={state.open} title="离开当前页面" confirmActionKey="ui.discardChanges" policy={state.blocked ? { kind: 'block', reason: state.reason } : { kind: 'allow' }}
      onConfirm={() => port.choose(true)} onCancel={() => port.choose(false)}>{state.reason}</UiConfirmDialog>
  </>
}
export function W2BridgeView({ page, pageProps, dialog }: {
  page: ComponentType<W2PageProps>; pageProps: W2PageProps; dialog: BridgeDialogPort
}) {
  return <FoundationProvider skin={pageProps.skin}>
    <BridgeDialog port={dialog} />{createElement(page, pageProps)}
  </FoundationProvider>
}
