import { mount } from '@vue/test-utils'
import { afterEach, expect, it } from 'vitest'
import { EditorView } from '@codemirror/view'
import { applySkin } from '@/themes/state'
import CodeMergeEditor from './CodeMergeEditor.vue'
import { nextTick } from 'vue'

afterEach(() => applySkin('tech-blue', false))

it('更换皮肤更新编辑器明暗模式并保留内容、光标和已有编辑器实例', async () => {
  const wrapper = mount(CodeMergeEditor, { props: { modelValue: 'original', language: 'java' }, attachTo: document.body })
  const host = wrapper.get('.cm-editor').element as HTMLElement
  const view = EditorView.findFromDOM(host)!
  view.dispatch({ changes: { from: 0, to: 8, insert: 'unsaved text' }, selection: { anchor: 4 } })
  const before = wrapper.emitted('update:modelValue')?.length
  applySkin('github-white', false)
  await nextTick()
  expect(EditorView.findFromDOM(host)).toBe(view)
  expect(view.state.doc.toString()).toBe('unsaved text')
  expect(view.state.selection.main.head).toBe(4)
  expect(view.state.facet(EditorView.darkTheme)).toBe(false)
  expect(wrapper.emitted('update:modelValue')?.length).toBe(before)
  applySkin('tech-blue', false)
  await nextTick()
  expect(view.state.facet(EditorView.darkTheme)).toBe(true)
  expect(view.state.doc.toString()).toBe('unsaved text')
  wrapper.unmount()
})
