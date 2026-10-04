import { createElement,useState } from 'react'
import { fireEvent } from '@testing-library/react'
import { afterEach,expect,it } from 'vitest'
import { applySkin } from '@/themes/state'
import { MergeEditor } from '@/pages/w4/publication/MergeEditor'
import { mount,flushPromises } from '@/pages/w6-tests/ordinary/render'
afterEach(()=>applySkin('tech-blue',false))
function Editor(){const [value,setValue]=useState('original');return createElement(MergeEditor,{value,baseline:'original',path:'Main.java',activeIndex:0,onChange:setValue})}
it('更换皮肤更新编辑器明暗模式并保留内容、光标和已有编辑器实例',async()=>{const wrapper=mount(Editor,{props:{}}),host=wrapper.get('textarea').element as HTMLTextAreaElement;fireEvent.change(host,{target:{value:'unsaved text'}});host.focus();host.setSelectionRange(4,4);applySkin('github-white',false);await flushPromises();expect(wrapper.get('textarea').element).toBe(host);expect(host.value).toBe('unsaved text');expect(host.selectionStart).toBe(4);expect(document.documentElement.dataset.skin).toBe('github-white');expect(document.activeElement).toBe(host);applySkin('tech-blue',false);await flushPromises();expect(document.documentElement.dataset.skin).toBe('tech-blue');expect(host.value).toBe('unsaved text');expect(wrapper.get('textarea').element).toBe(host);wrapper.unmount()})
