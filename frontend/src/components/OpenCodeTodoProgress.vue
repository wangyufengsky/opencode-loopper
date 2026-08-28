<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { TaskSessionActivity, TaskSessionTodo } from '@/types/domain'
import { displayLabel } from '@/utils/displayLabels'

const props = defineProps<{
  todos: TaskSessionTodo[]
  capability?: TaskSessionActivity['todoCapability']
  detail?: string
  truncated?: boolean
  expanded: boolean
}>()

const emit = defineEmits<{ 'update:expanded': [value: boolean] }>()

const completedCount = computed(() => props.todos.filter((todo) => todo.status === 'COMPLETED').length)
const currentTodo = computed(() => props.todos.find((todo) => todo.status === 'IN_PROGRESS')
  ?? props.todos.find((todo) => todo.status === 'PENDING')
  ?? props.todos[props.todos.length - 1])
const secondaryTodos = computed(() => props.todos.filter((todo) => todo.id !== currentTodo.value?.id))

function todoIcon(todo: TaskSessionTodo, compact = false) {
  if (todo.status === 'COMPLETED') return compact ? 'lucide:check' : 'lucide:circle-check'
  if (todo.status === 'IN_PROGRESS') return 'lucide:loader-circle'
  if (todo.status === 'CANCELLED') return compact ? 'lucide:x' : 'lucide:circle-x'
  return 'lucide:circle'
}
</script>

<template>
  <section class="todo-panel" aria-label="OpenCode 实施计划" aria-live="polite">
    <header class="todo-overview">
      <div class="todo-heading">
        <Icon icon="lucide:list-checks" width="18" aria-hidden="true" />
        <div><strong>OpenCode 实施进度</strong><small>当前阶段的非权威执行清单</small></div>
      </div>
      <span v-if="todos.length" class="todo-count">{{ completedCount }} / {{ todos.length }}</span>
      <span v-else class="todo-capability">{{ displayLabel(capability) }}</span>
    </header>
    <div v-if="todos.length" class="todo-track" aria-hidden="true">
      <i v-for="todo in todos" :key="todo.id" :class="`todo-track-${todo.status.toLowerCase()}`" />
    </div>
    <p v-if="detail" class="todo-detail">{{ detail }}</p>
    <p v-if="capability === 'AVAILABLE' && !todos.length" class="todo-empty">OpenCode 暂未提供实施项。</p>
    <div v-if="currentTodo" :class="['todo-current', `todo-${currentTodo.status.toLowerCase()}`]">
      <span class="todo-state"><Icon :icon="todoIcon(currentTodo)" width="16" aria-hidden="true" /></span>
      <span><strong>{{ currentTodo.content }}</strong><small>{{ displayLabel(currentTodo.status) }}{{ currentTodo.priority ? ` · ${displayLabel(currentTodo.priority)}优先级` : '' }}</small></span>
      <button
        v-if="secondaryTodos.length"
        type="button"
        class="todo-toggle"
        :aria-expanded="expanded"
        :aria-label="expanded ? '收起 OpenCode 实施清单' : '展开 OpenCode 实施清单'"
        @click="emit('update:expanded', !expanded)"
      >{{ expanded ? '收起清单' : '展开清单' }}</button>
    </div>
    <ol v-if="expanded && secondaryTodos.length" class="todo-list">
      <li v-for="todo in secondaryTodos" :key="todo.id" :class="`todo-${todo.status.toLowerCase()}`">
        <span class="todo-state"><Icon :icon="todoIcon(todo, true)" width="14" aria-hidden="true" /></span>
        <span>{{ todo.content }}</span><small>{{ displayLabel(todo.status) }}</small>
      </li>
    </ol>
    <footer v-if="truncated">清单过长，当前显示安全截断后的投影。</footer>
  </section>
</template>

<style scoped>
.todo-panel { margin: 0 0 14px; overflow: hidden; border: 1px solid rgb(66 92 128 / 72%); border-radius: 11px; background: #101827; }
.todo-overview { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 12px 14px 10px; }
.todo-heading { display: flex; min-width: 0; align-items: center; gap: 9px; }
.todo-heading > svg { flex: 0 0 auto; color: var(--color-accent-cyan); }
.todo-heading > div { display: grid; min-width: 0; gap: 2px; }
.todo-heading strong { color: var(--color-text-primary); font-size: 13px; line-height: 1.35; }
.todo-heading small { color: var(--color-text-muted); font-size: 10px; line-height: 1.4; }
.todo-count,.todo-capability { flex: 0 0 auto; padding: 5px 8px; border-radius: 999px; color: #b8c6da; background: #18263b; font: 700 10px/1 var(--font-code); font-variant-numeric: tabular-nums; }
.todo-capability { color: var(--color-text-muted); }
.todo-track { display: flex; gap: 3px; padding: 0 14px; }
.todo-track i { min-width: 5px; height: 4px; flex: 1 1 0; border-radius: 999px; background: #26344b; }
.todo-track .todo-track-completed { background: var(--color-success); }
.todo-track .todo-track-in_progress { background: var(--color-accent-cyan); box-shadow: 0 0 12px rgb(34 211 238 / 30%); }
.todo-track .todo-track-cancelled { background: var(--color-task-danger); }
.todo-current { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; margin: 11px 12px 12px; padding: 10px 11px; border-radius: 8px; color: var(--color-text-secondary); background: #141f32; }
.todo-current > span:nth-child(2) { display: grid; min-width: 0; gap: 3px; }
.todo-current strong { overflow: hidden; color: var(--color-text-primary); font-size: 12px; font-weight: 650; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.todo-current small { color: var(--color-text-muted); font-size: 10px; line-height: 1.35; }
.todo-toggle { padding: 5px 7px; border: 0; border-radius: 5px; color: var(--color-text-secondary); background: transparent; font-size: 10px; cursor: pointer; touch-action: manipulation; }
.todo-toggle:hover { color: var(--color-text-primary); background: var(--color-bg-hover); }
.todo-toggle:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 2px; }
.todo-list { display: grid; max-height: 156px; margin: 0 12px 12px; padding: 0; overflow: auto; overscroll-behavior: contain; border: 1px solid var(--color-border-default); border-radius: 8px; background: var(--color-border-default); list-style: none; }
.todo-list li { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: 9px; min-height: 36px; padding: 7px 9px; color: var(--color-text-secondary); background: #0d1523; font-size: 11px; line-height: 1.45; }
.todo-list li + li { border-top: 1px solid var(--color-border-default); }
.todo-list li > span:nth-child(2) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.todo-list li small { color: var(--color-text-muted); font-size: 9px; }
.todo-state { display: inline-flex; color: var(--color-text-muted); }
.todo-in_progress .todo-state { color: var(--color-accent-cyan); }
.todo-in_progress .todo-state :deep(svg) { animation: thinking-spin 1.3s linear infinite; }
.todo-completed { opacity: .74; }
.todo-completed .todo-state { color: var(--color-success); }
.todo-cancelled { opacity: .56; }
.todo-cancelled .todo-state { color: var(--color-task-danger); }
.todo-detail,.todo-empty,.todo-panel footer { margin: 0; padding: 10px 14px; color: var(--color-text-muted); font-size: 10px; line-height: 1.5; }
.todo-panel footer { border-top: 1px solid var(--color-border-default); color: var(--color-session-warning); }
@keyframes thinking-spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) { .todo-current { grid-template-columns: auto minmax(0, 1fr); }.todo-toggle { grid-column: 2; justify-self: start; padding-left: 0; } }
@media (prefers-reduced-motion: reduce) { .todo-in_progress .todo-state :deep(svg) { animation: none; } }
</style>
