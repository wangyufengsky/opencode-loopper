import { useSyncExternalStore } from 'react'
import { Discussion } from '@/pages/w5/designer/Discussion'
import type { DesignerController } from '@/pages/w5/designer/controller'
import { useTestPage } from './react-test-root'
/** The production Discussion and its original retained Designer owner, not a second activity fetcher. */
export function DesignerActivityProjection({owner}:{owner:DesignerController}){const state=useSyncExternalStore(owner.subscribe,owner.getSnapshot,owner.getSnapshot);return <Discussion owner={owner} state={state} skin={useTestPage().skin} stopAttachment={()=>{}}/>}
import { useState, useEffect, type ReactNode } from 'react'
import { LoopSpecEditor as ActualSpec } from '@/pages/w5/designer/LoopSpecEditor'
/** Controlled UI parent: the production structured editor owns every field conversion. */
export function StructuredSpec({modelValue,onChange,afterStages}:{modelValue:string;onChange?:(value:string)=>void;afterStages?:ReactNode}){const [value,setValue]=useState(modelValue);useEffect(()=>setValue(modelValue),[modelValue]);return <ActualSpec source={value} onChange={next=>{setValue(next);onChange?.(next)}} afterStages={afterStages}/>}
