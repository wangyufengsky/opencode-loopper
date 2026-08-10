<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'

const props = withDefaults(defineProps<{ modelValue: string; readonly?: boolean; ariaLabel?: string }>(), { readonly: false, ariaLabel: '代码编辑器' })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined

onMounted(() => {
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(), keymap.of([]), EditorView.lineWrapping,
        EditorView.editable.of(!props.readonly), EditorState.readOnly.of(props.readonly),
        EditorView.contentAttributes.of({ 'aria-label': props.ariaLabel }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        }),
        EditorView.theme({
          '&': { height: '100%', background: '#07101d', color: '#dbeafe', fontSize: '11px' },
          '.cm-content': { fontFamily: 'var(--font-code)', padding: '10px 0' },
          '.cm-gutters': { background: '#091321', color: '#5d6d86', border: 'none' },
          '.cm-activeLine, .cm-activeLineGutter': { background: 'rgba(56, 189, 248, .06)' },
          '.cm-cursor': { borderLeftColor: '#22d3ee' }, '.cm-selectionBackground': { background: 'rgba(59, 130, 246, .28) !important' },
        }, { dark: true }),
      ],
    }),
  })
})

watch(() => props.modelValue, (value) => {
  if (!editor || editor.state.doc.toString() === value) return
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})

onBeforeUnmount(() => editor?.destroy())
</script>

<template><div ref="host" class="code-merge-editor" /></template>

<style scoped>.code-merge-editor { min-height: 0; height: 100%; overflow: hidden; }</style>
