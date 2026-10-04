import { useSyncExternalStore } from 'react'
import { PptPlanEditor } from '@/pages/w3/ppt/Plan'
import { PptProperties } from '@/pages/w3/ppt/Properties'
import type { PptStudioController } from '@/pages/w3/ppt/controller'
export function PlanProjection({owner}:{owner:PptStudioController}){const state=useSyncExternalStore(owner.subscribe,owner.getSnapshot);return <PptPlanEditor owner={owner} state={state} confirm={(_title,action)=>action()}/>}
export function PropertiesProjection({owner}:{owner:PptStudioController}){const state=useSyncExternalStore(owner.subscribe,owner.getSnapshot),slide=state.deck?.slides[0];return slide?<PptProperties owner={owner} state={state} slide={slide} element={slide.elements[0]}/>:null}
