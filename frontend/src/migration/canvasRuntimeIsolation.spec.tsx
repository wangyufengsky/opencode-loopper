import { useState } from 'react'
import { act, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { captureCanvasRuntime, CANVAS_RUNTIME_STORAGE } from './canvasRuntime'
function Canvas(){const[runtime]=useState(()=>{try{return captureCanvasRuntime('/designer',localStorage)}catch{return'react'}});return <div data-runtime={runtime}/>}
describe('mounted canvas runtime isolation',()=>{
 afterEach(()=>{localStorage.removeItem(CANVAS_RUNTIME_STORAGE)})
 it('does not remount or mutate current runtime on preference/storage events',async()=>{localStorage.removeItem(CANVAS_RUNTIME_STORAGE);const view=render(<Canvas/>),original=view.container.firstElementChild;localStorage.setItem(CANVAS_RUNTIME_STORAGE,JSON.stringify({documents:'vue'}));act(()=>window.dispatchEvent(new StorageEvent('storage',{key:CANVAS_RUNTIME_STORAGE})));view.rerender(<Canvas/>);expect(original?.getAttribute('data-runtime')).toBe('react');expect(view.container.firstElementChild).toBe(original);view.unmount();const next=render(<Canvas/>);expect(next.container.firstElementChild?.getAttribute('data-runtime')).toBe('react');next.unmount()})
 it('keeps React available when localStorage access is denied',()=>{const getter=vi.spyOn(window,'localStorage','get').mockImplementation(()=>{throw new Error('blocked')});const view=render(<Canvas/>);expect(view.container.firstElementChild?.getAttribute('data-runtime')).toBe('react');view.unmount();getter.mockRestore()})
})
