import {screen,within,fireEvent} from '@testing-library/react'
import {act} from 'react'
import {flushPromises,Dom} from './render'
export async function action(key:string,root:ParentNode=document){const target=root.querySelector<HTMLButtonElement>(`button[data-semantic="${key}"]`);if(!target)throw new Error(`Missing real action ${key}`);act(()=>fireEvent.click(target));await flushPromises();return target}
export async function confirm(key:string,title?:string){const dialogs=screen.getAllByRole('dialog'),dialog=title?dialogs.find(node=>node.querySelector('.ant-modal-title')?.textContent===title):dialogs[dialogs.length-1]!;if(!dialog)throw new Error(`Missing actual dialog ${title}`);return action(key,dialog)}
export async function change(label:string,value:string){act(()=>fireEvent.change(screen.getByLabelText(label),{target:{value}}));await flushPromises()}
export function body(){return new Dom(document.body)}
export {screen,within,fireEvent,act,flushPromises}
