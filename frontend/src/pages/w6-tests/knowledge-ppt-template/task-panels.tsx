import {useMemo} from 'react'
import type {Artifact,Task} from '@/types/domain'
import {taskFixture} from '@/pages/w4/task/test-support'
import {createTaskEvidenceController} from '@/pages/w4/task/evidenceController'
import {TemplateReportsPanel,SnapshotBatchesPanel,SnapshotReviewPartialReportPanel} from '@/pages/w4/task/TaskEvidencePanels'
import {TemplateBatchRecoveryPanel,TemplateSessionDiagnosticsPanel} from '@/pages/w3/templates/runs/RecoveryPanels'
import {useTestPage} from './react-test-root'
export function ReportsProjection({taskId,artifacts,accepted,dualReviewRequired,loadingMetadata,metadataError,onReload}:{taskId:string;artifacts:Artifact[];accepted?:boolean;dualReviewRequired?:boolean;loadingMetadata?:boolean;metadataError?:string;onReload?:()=>void}){
 const page=useTestPage(),owner=useMemo(()=>createTaskEvidenceController(taskId),[taskId]);const task={...taskFixture(taskId),status:accepted?'COMPLETED':'RUNNING',artifacts,templateProgress:{dualReviewRequired:dualReviewRequired!==false}} as Task
 return <TemplateReportsPanel task={task} page={page} owner={owner} loadingMetadata={loadingMetadata} metadataError={metadataError} parent={{canStartWrite:()=>true,registerChild:()=>()=>{},refresh:async()=>{onReload?.()}}}/>
}
export function BatchesProjection({task}:{task:Task}){const page=useTestPage(),owner=useMemo(()=>createTaskEvidenceController(task.id),[task.id]);return <SnapshotBatchesPanel owner={owner} page={page}/>}
export function PartialProjection({taskId}:{taskId:string}){const page=useTestPage(),owner=useMemo(()=>createTaskEvidenceController(taskId),[taskId]);return <SnapshotReviewPartialReportPanel owner={owner} page={page}/>}
export function BatchRecoveryProjection({task}:{task:Task}){const page=useTestPage();return <TemplateBatchRecoveryPanel kind="task" id={task.id} props={page} active={['RUNNING','WAITING_INPUT'].includes(task.status)} taskStatus={task.status} revision={`${task.status}:${task.version}`}/>}
export function DiagnosticProjection({taskId,active,onSelect}:{taskId:string;active:boolean;onSelect?:(key:string)=>void}){return <TemplateSessionDiagnosticsPanel taskId={taskId} active={active} props={useTestPage()} onSelect={onSelect}/>}
import {TemplateProgress} from '@/pages/w4/task/TaskDetailPage'
export function ProgressProjection({task}:{task:Task}){const page=useTestPage(),owner=useMemo(()=>createTaskEvidenceController(task.id),[task.id]);return <TemplateProgress task={task} page={page} evidence={owner} parent={{canStartWrite:()=>true,registerChild:()=>()=>{},refresh:async()=>undefined}}/>}
