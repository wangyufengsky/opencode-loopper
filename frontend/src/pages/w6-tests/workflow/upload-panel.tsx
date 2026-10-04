import { useMemo } from 'react'
import { DocumentInput } from '@/pages/w5/requirements/Inputs'
import { createRequirementController } from '@/pages/w5/requirements/controller'
import { requirement as requirementFixture, execution } from '@/components/workflow/workflowRunTestFixtures'
import { useTestPage } from './react-test-root'
export function WorkflowDocumentInput({ requirement, version, revision, value, title, onChange }: { requirement:string;version:number;revision:number;value:string;title:string;onChange?(value:string):void }) {
 const parent=useMemo(()=>{const owner=createRequirementController(requirement),base=requirementFixture({id:requirement,revision}),snapshot=execution();snapshot.execution.id=requirement;snapshot.execution.version=version;snapshot.control.id=requirement;owner.patch({base,execution:snapshot,readable:true,inputValues:{document:value}});const setInput=owner.setInput.bind(owner);owner.setInput=(name,next,caller)=>{const previous=owner.getSnapshot().inputValues[name];setInput(name,next,caller);if(previous!==owner.getSnapshot().inputValues[name])onChange?.(next)};return owner},[requirement])
 parent.patch({inputValues:{document:value},base:{...parent.getSnapshot().base!,revision},execution:{...parent.getSnapshot().execution!,execution:{...parent.getSnapshot().execution!.execution,version}}})
 return <DocumentInput page={useTestPage()} parent={parent} field={{name:'document',title,kind:'DOCUMENT',required:false}} disabled={false}/>
}
