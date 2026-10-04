import {afterEach,vi} from 'vitest'
import {coreFixture} from '@/pages/w2/core/coreTestHelpers'
import {HistoryDrawer} from '@/pages/w3/templates/catalog/HistoryDrawer'
import {archiveReads,createTemplateHistoryArchiveController,decodeHistoricalWorkspace,decodeHistoricalRule,decodeHistoricalTemplate,decodeHistoricalRun} from '@/pages/w3/templates/catalog/history'
import {historicalWorkspace,historicalTemplate,historicalRun,historicalRule} from '@/pages/w3/templates/catalog/fixtures'
import {mount,flushPromises} from './render'
const disposers:(()=>void)[]=[]
afterEach(()=>{disposers.splice(0).forEach(fn=>fn())})
export function archiveFixture(){
 const current={workspace:decodeHistoricalWorkspace(historicalWorkspace)}, reads={...archiveReads,workspace:vi.fn(async()=>current.workspace),templates:vi.fn(async()=>[decodeHistoricalTemplate(historicalTemplate)]),template:vi.fn(async()=>decodeHistoricalTemplate(historicalTemplate)),versions:vi.fn(async()=>decodeHistoricalTemplate(historicalTemplate).versions),rules:vi.fn(async()=>[decodeHistoricalRule(historicalRule)]),ruleRuns:vi.fn(async()=>[decodeHistoricalRun(historicalRun)]),runs:vi.fn(async()=>({runs:[decodeHistoricalRun(historicalRun)],serverTime:'now'})),latestExport:vi.fn(async()=>'{"templates":[]}'),workspaceExport:vi.fn(async()=>'{"formatVersion":1,"templates":[],"rules":[]}')}
 const owner=createTemplateHistoryArchiveController(reads),f=coreFixture(),view=mount(HistoryDrawer,{props:{owner,props:f.props}})
 disposers.push(()=>{owner.retire(true);f.dispose()})
 return{owner,reads,current,view,async open(){await owner.open();await flushPromises()}}
}
