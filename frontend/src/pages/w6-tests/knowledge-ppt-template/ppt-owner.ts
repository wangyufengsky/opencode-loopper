import { afterEach } from 'vitest'
import { createPptStudioController, studioActive, studioEditable, type PptStudioController } from '@/pages/w3/ppt/controller'
/** Test vocabulary adapter. Every read/write/stream is owned by the actual production studio controller. */
export function createStudioContract() {
  let owner: ReturnType<typeof createPptStudioController> | undefined, detach: (()=>void) | undefined
  function close() { detach?.();detach=undefined;owner?.retire(true) }
  const harness = {
    get owner() { if (!owner) throw new Error('Load the actual studio owner first'); return owner },
    async load(id:string) { if (owner?.identity.id===id && !owner.getSnapshot().loading) return owner.refresh();close();owner=createPptStudioController(id);detach=owner.attachView();await owner.start() },
    close,
    get document(){return owner?.getSnapshot().document??null},get deck(){return owner?.getSnapshot().deck??null},get plan(){return owner?.getSnapshot().plan},get agent(){return owner?.getSnapshot().agent??null},get generation(){return owner?.getSnapshot().generation??null},get pending(){return owner?.getSnapshot().pending??null},get error(){return owner?.getSnapshot().error??''},get issues(){return owner?.getSnapshot().issues??[]},get checkedRevision(){return owner?.getSnapshot().checkedRevision??null},
    get active(){return !!owner&&studioActive(owner.getSnapshot())},get generationActive(){return !!owner?.getSnapshot().generation&&['PLANNING','PRODUCING','PREVIEWING','EXPORTING','STOPPING'].includes(owner.getSnapshot().generation!.state)},get editable(){return !!owner&&studioEditable(owner.getSnapshot())},
    refresh:()=>harness.owner.refresh(),retryPending:()=>harness.owner.retryOriginal(),check:()=>harness.owner.check(),
    send:(...args:Parameters<PptStudioController['send']>)=>harness.owner.send(...args),reply:(...args:Parameters<PptStudioController['reply']>)=>harness.owner.reply(...args),operations:(...args:Parameters<PptStudioController['operations']>)=>harness.owner.operations(...args),
    createJob:(...args:Parameters<PptStudioController['createJob']>)=>harness.owner.createJob(...args),generate:(...args:Parameters<PptStudioController['generate']>)=>harness.owner.generate(...args),confirmRequirements:()=>harness.owner.confirmRequirements(),resume:()=>harness.owner.resume(),adjustAndResume:(value:string)=>harness.owner.resume(value),stop:()=>harness.owner.stop(),
  }
  return harness
}
const harnesses=new Set<ReturnType<typeof createStudioContract>>()
let current:ReturnType<typeof createStudioContract>|undefined
export function resetPptHarness(){current=undefined}
export function usePptStore(){if(!current){current=createStudioContract();harnesses.add(current)}return current}
afterEach(()=>{harnesses.forEach(value=>value.close());harnesses.clear();current=undefined})
