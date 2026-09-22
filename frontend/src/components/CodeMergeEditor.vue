<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { java } from '@codemirror/lang-java'
import { json } from '@codemirror/lang-json'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { currentSkin } from '@/themes/state'
import { Compartment, EditorState, StateEffect, StateField } from '@codemirror/state'
import { Decoration, type DecorationSet, EditorView, keymap, lineNumbers } from '@codemirror/view'

const props = withDefaults(defineProps<{
  modelValue: string
  readonly?: boolean
  ariaLabel?: string
  language?: 'java' | 'json' | 'plain'
  lineWrapping?: boolean
  firstLineNumber?: number
  highlightedLines?: number[]
  changedLines?: number[]
  conflictLines?: number[]
  activeConflictLines?: number[]
}>(), {
  readonly: false,
  ariaLabel: '代码编辑器',
  language: 'plain',
  lineWrapping: false,
  firstLineNumber: 1,
  highlightedLines: () => [],
  changedLines: () => [],
  conflictLines: () => [],
  activeConflictLines: () => [],
})
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLElement>()
let editor: EditorView | undefined

interface MergeDecorations {
  highlighted: number[]
  changed: number[]
  conflicts: number[]
  active: number[]
}

const setMergeDecorations = StateEffect.define<MergeDecorations>()
const editorAppearance = new Compartment()
const highlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--renderer-keyword)' },
  { tag: [tags.string, tags.regexp], color: 'var(--renderer-string)' },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--renderer-number)' },
  { tag: [tags.typeName, tags.className], color: 'var(--renderer-type)' },
  { tag: tags.comment, color: 'var(--renderer-comment)', fontStyle: 'italic' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: 'var(--renderer-function)' },
  { tag: tags.invalid, color: 'var(--renderer-invalid)' },
])

function buildDecorations(state: EditorState, value: MergeDecorations) {
  const highlighted = new Set(value.highlighted)
  const changed = new Set(value.changed)
  const conflicts = new Set(value.conflicts)
  const active = new Set(value.active)
  const decorations = []
  for (let line = 1; line <= state.doc.lines; line += 1) {
    let className = ''
    if (highlighted.has(line)) className = 'cm-evidence-highlight'
    else if (active.has(line)) className = 'cm-merge-conflict-active'
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
    highlighted: props.highlightedLines,
    changed: props.changedLines,
    conflicts: props.conflictLines,
    active: props.activeConflictLines,
  }) })
}

function editorTheme() {
  return EditorView.theme({
    '&': { height: '100%', background: 'var(--renderer-editor)', color: 'var(--renderer-editorText)', fontSize: '11px' },
    '.cm-content': { fontFamily: 'var(--font-code)', padding: '10px 0' },
    '.cm-gutters': { background: 'var(--renderer-gutter)', color: 'var(--renderer-gutterText)', border: 'none' },
    '.cm-activeLine, .cm-activeLineGutter': { background: 'rgb(var(--renderer-active-rgb) / .06)' },
    '.cm-line.cm-evidence-highlight': { background: 'rgb(var(--renderer-evidence-rgb) / .13)', boxShadow: 'inset 3px 0 0 var(--renderer-evidence)' },
    '.cm-line.cm-merge-changed': { background: 'rgb(var(--renderer-changed-rgb) / .11)', boxShadow: 'inset 3px 0 0 rgb(var(--renderer-changedBorder-rgb) / .7)' },
    '.cm-line.cm-merge-conflict': { background: 'rgb(var(--renderer-conflict-rgb) / .12)', boxShadow: 'inset 3px 0 0 rgb(var(--renderer-conflict-rgb) / .68)' },
    '.cm-line.cm-merge-conflict-active': { background: 'rgb(var(--renderer-conflictActive-rgb) / .22)', boxShadow: 'inset 3px 0 0 var(--renderer-conflictActive)' },
    '.cm-cursor': { borderLeftColor: 'var(--renderer-evidence)' }, '.cm-selectionBackground': { background: 'rgb(var(--renderer-selection-rgb) / .28) !important' },
  }, { dark: currentSkin.value.colorScheme === 'dark' })
}

watch(currentSkin, () => {
  editor?.dispatch({ effects: editorAppearance.reconfigure(editorTheme()) })
})

onMounted(() => {
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers({ formatNumber: line => String(line + props.firstLineNumber - 1) }), keymap.of([]), languageExtension(), syntaxHighlighting(highlightStyle), mergeDecorationField,
        ...(props.lineWrapping ? [EditorView.lineWrapping] : []),
        EditorView.editable.of(!props.readonly), EditorState.readOnly.of(props.readonly),
        EditorView.contentAttributes.of({ 'aria-label': props.ariaLabel }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit('update:modelValue', update.state.doc.toString())
        }),
        editorAppearance.of(editorTheme()),
      ],
    }),
  })
  updateDecorations()
})

watch(() => props.modelValue, (value) => {
  if (!editor || editor.state.doc.toString() === value) return
  editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})

watch(() => [props.highlightedLines, props.changedLines, props.conflictLines, props.activeConflictLines], updateDecorations, { deep: true })

function scrollToLine(line: number) {
  if (!editor || line < 1) return
  const bounded = Math.min(line, editor.state.doc.lines)
  editor.dispatch({ effects: EditorView.scrollIntoView(editor.state.doc.line(bounded).from, { y: 'center' }) })
}

defineExpose({ scrollToLine })

onBeforeUnmount(() => editor?.destroy())
</script>

<template><div ref="host" class="code-merge-editor" /></template>

<style scoped>.code-merge-editor {min-height: 0;height: 100%;overflow: hidden; }</style>
