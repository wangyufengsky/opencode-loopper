<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { java } from '@codemirror/lang-java'
import { json } from '@codemirror/lang-json'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { EditorState, StateEffect, StateField } from '@codemirror/state'
import { Decoration, type DecorationSet, EditorView, keymap, lineNumbers } from '@codemirror/view'

const props = withDefaults(defineProps<{
  modelValue: string
  readonly?: boolean
  ariaLabel?: string
  language?: 'java' | 'json' | 'plain'
  changedLines?: number[]
  conflictLines?: number[]
  activeConflictLines?: number[]
}>(), {
  readonly: false,
  ariaLabel: '代码编辑器',
  language: 'plain',
  changedLines: () => [],
  conflictLines: () => [],
  activeConflictLines: () => [],
})
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined

interface MergeDecorations {
  changed: number[]
  conflicts: number[]
  active: number[]
}

const setMergeDecorations = StateEffect.define<MergeDecorations>()

function buildDecorations(state: EditorState, value: MergeDecorations) {
  const changed = new Set(value.changed)
  const conflicts = new Set(value.conflicts)
  const active = new Set(value.active)
  const decorations = []
  for (let line = 1; line <= state.doc.lines; line += 1) {
    let className = ''
    if (active.has(line)) className = 'cm-merge-conflict-active'
    else if (conflicts.has(line)) className = 'cm-merge-conflict'
    else if (changed.has(line)) className = 'cm-merge-changed'
    if (className) decorations.push(Decoration.line({ class: className }).range(state.doc.line(line).from))
  }
  return Decoration.set(decorations, true)
}

const mergeDecorationField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, transaction) {
    let next = value.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (effect.is(setMergeDecorations)) next = buildDecorations(transaction.state, effect.value)
    }
    return next
  },
  provide: (field) => EditorView.decorations.from(field),
})

function languageExtension() {
  if (props.language === 'java') return java()
  if (props.language === 'json') return json()
  return []
}

function updateDecorations() {
  editor?.dispatch({ effects: setMergeDecorations.of({
    changed: props.changedLines,
    conflicts: props.conflictLines,
    active: props.activeConflictLines,
  }) })
}

onMounted(() => {
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(), keymap.of([]), languageExtension(), syntaxHighlighting(defaultHighlightStyle, { fallback: true }), mergeDecorationField,
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
          '.cm-line.cm-merge-changed': { background: 'rgba(34, 197, 94, .11)', boxShadow: 'inset 3px 0 0 rgba(74, 222, 128, .7)' },
          '.cm-line.cm-merge-conflict': { background: 'rgba(248, 113, 113, .12)', boxShadow: 'inset 3px 0 0 rgba(248, 113, 113, .68)' },
          '.cm-line.cm-merge-conflict-active': { background: 'rgba(251, 146, 60, .22)', boxShadow: 'inset 3px 0 0 #fb923c' },
          '.cm-cursor': { borderLeftColor: '#22d3ee' }, '.cm-selectionBackground': { background: 'rgba(59, 130, 246, .28) !important' },
        }, { dark: true }),
      ],
    }),
  })
  updateDecorations()
})

watch(() => props.modelValue, (value) => {
  if (!editor || editor.state.doc.toString() === value) return
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})

watch(() => [props.changedLines, props.conflictLines, props.activeConflictLines], updateDecorations, { deep: true })

function scrollToLine(line: number) {
  if (!editor || line < 1) return
  const bounded = Math.min(line, editor.state.doc.lines)
  editor.dispatch({ effects: EditorView.scrollIntoView(editor.state.doc.line(bounded).from, { y: 'center' }) })
}

defineExpose({ scrollToLine })

onBeforeUnmount(() => editor?.destroy())
</script>

<template><div ref="host" class="code-merge-editor" /></template>

<style scoped>.code-merge-editor { min-height: 0; height: 100%; overflow: hidden; }</style>
