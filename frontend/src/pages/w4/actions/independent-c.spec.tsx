/** Non-author regression: a real ambient GET changes the commit while its push confirmation waits. */
import { act,cleanup,fireEvent,render,screen,waitFor,within } from '@testing-library/react'
import { afterEach,expect,it,vi } from 'vitest'
import { FoundationProvider } from '@/foundation/provider'
import { semanticName } from '@/foundation/semanticRegistry'
import type { TaskPublicationOwner } from '../publication/controller'
import { TaskPublicationActions } from '../publication/TaskPublicationActions'
import { fixture,pageProps,task } from '../publication/test-support'
import { foundationDOM,flush } from './test-support'
let release=()=>{}
afterEach(()=>{cleanup();release();release=()=>{};vi.restoreAllMocks()})
it('independent ambient COMMITTED read changes the original commit and invalidates its already-open push confirmation',async()=>{
  foundationDOM();const f=fixture({state:'COMMITTED',deliveryState:'COMMITTED',commitSha:'original-commit'}),p=pageProps();release=p.retire
  render(<FoundationProvider skin={p.props.skin} reducedMotion><main className="ui-shell-main" tabIndex={-1}><TaskPublicationActions task={task()} page={p.props} parent={p.parent}/></main></FoundationProvider>)
  const trigger=await screen.findByRole('button',{name:semanticName('publication.push')});fireEvent.click(trigger);const dialog=screen.getByRole('dialog');expect(f.publish).not.toHaveBeenCalled()
  const reads=f.get.mock.calls.length;f.setPublication({state:'COMMITTED',deliveryState:'COMMITTED',commitSha:'new-commit'});vi.spyOn(Date,'now').mockReturnValue(Date.now()+31_000);fireEvent.focus(window)
  await waitFor(()=>expect(f.get).toHaveBeenCalledTimes(reads+1));await waitFor(()=>expect((Array.from(p.children)[0] as TaskPublicationOwner).getSnapshot().publication?.commitSha).toBe('new-commit'))
  // The original genuine modal still exists; no fake emit or disabled-input overwrite.
  await act(async()=>{fireEvent.click(within(dialog).getByRole('button',{name:semanticName('publication.push')}));await flush()});expect(f.publish).not.toHaveBeenCalled();expect(screen.getByRole('dialog')).toBe(dialog)
})
