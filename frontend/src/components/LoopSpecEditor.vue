<script setup lang="ts">
import { json } from '@codemirror/lang-json'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  ariaLabel?: string
}>(), { ariaLabel: 'LoopSpec JSON 编辑器' })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined

const darkTheme = EditorView.theme({
  '&': { minHeight: '464px', border: '1px solid #21304B', borderRadius: '6px', backgroundColor: '#080D18', color: '#C9D8EC' },
  '&.cm-focused': { outline: '2px solid #22D3EE', outlineOffset: '2px', borderColor: '#22D3EE' },
  '.cm-content, .cm-gutter': { fontFamily: 'var(--font-code)', fontSize: '11px', lineHeight: '1.65' },
  '.cm-content': { padding: '14px 0 18px' },
  '.cm-gutters': { minHeight: '464px', borderRight: '1px solid #1A2942', backgroundColor: '#0B1221', color: '#65738A' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'rgb(59 130 246 / 9%)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'rgb(59 130 246 / 32%) !important' },
  '.cm-cursor': { borderLeftColor: '#22D3EE' },
}, { dark: true })

function replaceDocument(value: string) {
  if (!editor || editor.state.doc.toString() === value) return
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
}

onMounted(() => {
  if (!host.value) return
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        json(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        EditorView.lineWrapping,
        EditorView.contentAttributes.of({ 'aria-label': props.ariaLabel, 'aria-multiline': 'true' }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        }),
        darkTheme,
      ],
    }),
  })
})

watch(() => props.modelValue, replaceDocument)
onBeforeUnmount(() => editor?.destroy())
</script>

<template><div ref="host" class="loop-spec-editor" /></template>
