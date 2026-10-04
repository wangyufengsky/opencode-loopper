import type { SnapshotPort } from '@/foundation/contracts/types'
export interface BridgeDialogSnapshot { open: boolean; reason: string; blocked: boolean; notice: string }
export interface BridgeDialogPort extends SnapshotPort<BridgeDialogSnapshot> { choose(allow: boolean): void }
