import { DocumentSourcesPanel as Sources, DocumentRequirementsPanel as Requirements, DocumentClarificationForm as Clarification, DocumentSupplementForm as Supplement } from '@/pages/w3/templates/runs/DocumentPanels'
import { ArtifactsPanel, SourceCoveragePanel as Coverage } from '@/pages/w3/templates/runs/ContentPanels'
import { documentRun } from '@/pages/w3/templates/catalog/fixtures'
import type { DocumentTemplateOverview } from '@/types/domain'
import { useTestPage } from './react-test-root'
export const completeRun=(run:DocumentTemplateOverview):DocumentTemplateOverview=>({...documentRun(run.id),...run,progress:{...documentRun().progress,...run.progress},files:(run.files ?? []).map(file=>({...documentRun().files[0],...file}))})
export function DocumentRequirementsPanel({run}:{run:DocumentTemplateOverview}){return <Requirements run={completeRun(run)} props={useTestPage()}/>}
export function DocumentSourcesPanel({run}:{run:DocumentTemplateOverview}){return <Sources run={completeRun(run)} props={useTestPage()}/>}
export function DocumentReportsPanel({runId,completed=false,count=0}:{runId:string;completed?:boolean;count?:number}){return <ArtifactsPanel kind="document" id={runId} revision={count} completed={completed} props={useTestPage()}/>}
export function SourceArtifactsPanel({runId,version=0,completed=false}:{runId:string;version?:number;completed?:boolean}){return <ArtifactsPanel kind="source" id={runId} revision={version} completed={completed} props={useTestPage()}/>}
export function SourceCoveragePanel({runId,revision,ready}:{runId:string;revision:string;ready:boolean}){return <Coverage id={runId} revision={revision} ready={ready} props={useTestPage()}/>}
export function DocumentClarificationForm({run,requirementKey,onUpdated}:{run:DocumentTemplateOverview;requirementKey:string;onUpdated?:(run:DocumentTemplateOverview)=>void}){return <Clarification run={completeRun(run)} requirementKey={requirementKey} props={useTestPage()} updated={value=>onUpdated?.(value)}/>}
export function DocumentSupplementForm({run,onUpdated}:{run:DocumentTemplateOverview;onUpdated?:(run:DocumentTemplateOverview)=>void}){return <Supplement run={completeRun(run)} props={useTestPage()} updated={value=>onUpdated?.(value)}/>}
