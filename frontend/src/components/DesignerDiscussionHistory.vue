<script setup lang="ts">
import { Icon } from '@iconify/vue'
import type { DesignerAnsweredQuestion } from '@/types/domain'
import { formatDateTime } from '@/utils/dateTime'

defineProps<{ entries: DesignerAnsweredQuestion[] }>()

function scopeLabel(scope?: string) {
  return !scope || scope === 'REQUIREMENT' ? '整体需求' : scope
}
</script>

<template>
  <details v-if="entries.length" class="designer-discussion-history">
    <summary><span>需求讨论</span><Icon icon="lucide:chevron-down" width="16" /></summary>
    <div class="discussion-history-body">
      <article v-for="entry in entries" :key="`${entry.id}-${entry.discussionRevision ?? 0}`" class="discussion-turn">
        <header>
          <span>{{ scopeLabel(entry.scope) }}<template v-if="entry.discussionRevision"> · R{{ entry.discussionRevision }}</template></span>
          <time v-if="entry.answeredAt" :datetime="entry.answeredAt">{{ formatDateTime(entry.answeredAt) }}</time>
        </header>
        <section v-for="(prompt, index) in entry.questions" :key="`${entry.id}-${index}`" class="answered-prompt">
          <p>{{ prompt.header || `问题 ${index + 1}` }}</p>
          <h3>{{ prompt.question }}</h3>
          <ul v-if="prompt.options.length" class="answered-options" aria-label="设计者提供的选项">
            <li v-for="option in prompt.options" :key="option.label" :class="{ selected: prompt.answers.includes(option.label) }">
              <Icon :icon="prompt.answers.includes(option.label) ? 'lucide:circle-check-big' : 'lucide:circle'" width="15" />
              <span><b>{{ option.label }}</b><small v-if="option.description">{{ option.description }}</small></span>
            </li>
          </ul>
          <div class="final-answer">
            <span>用户最终回答</span>
            <strong>{{ prompt.answers.join('、') || '未记录' }}</strong>
          </div>
        </section>
      </article>
    </div>
  </details>
</template>

<style scoped>
.designer-discussion-history { margin: 14px 0; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 12px; background: rgb(15 23 42 / 54%); }
.designer-discussion-history summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; color: var(--color-text-secondary); font-size: 11px; font-weight: 800; cursor: pointer; list-style: none; }
.designer-discussion-history summary::-webkit-details-marker { display: none; }
.designer-discussion-history summary svg { transition: transform .18s ease; }
.designer-discussion-history[open] summary { border-bottom: 1px solid var(--color-border-default); color: var(--color-accent-cyan); }
.designer-discussion-history[open] summary svg { transform: rotate(180deg); }
.discussion-history-body { display: grid; }
.discussion-turn { padding: 14px; border-bottom: 1px solid var(--color-border-default); }
.discussion-turn:last-child { border-bottom: 0; }
.discussion-turn > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: var(--color-text-muted); font: 700 9px/1.4 var(--font-code); }
.answered-prompt + .answered-prompt { margin-top: 16px; padding-top: 16px; border-top: 1px dashed var(--color-border-default); }
.answered-prompt > p { margin: 0 0 5px; color: #a78bfa; font: 800 9px/1.2 var(--font-code); letter-spacing: .08em; text-transform: uppercase; }
.answered-prompt h3 { margin: 0 0 10px; color: var(--color-text-primary); font-size: 12px; line-height: 1.6; }
.answered-options { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.answered-options li { display: flex; align-items: flex-start; gap: 8px; padding: 8px 10px; border: 1px solid var(--color-border-default); border-radius: 8px; color: var(--color-text-muted); }
.answered-options li.selected { border-color: rgb(34 211 238 / 42%); background: rgb(34 211 238 / 8%); color: var(--color-accent-cyan); }
.answered-options li > span { display: grid; gap: 2px; }
.answered-options b { color: var(--color-text-primary); font-size: 10px; }
.answered-options small { font-size: 9px; line-height: 1.4; }
.final-answer { display: grid; grid-template-columns: auto 1fr; gap: 10px; margin-top: 9px; padding: 9px 10px; border-radius: 8px; background: rgb(34 197 94 / 8%); }
.final-answer span { color: #4ade80; font: 800 9px/1.5 var(--font-code); }
.final-answer strong { color: var(--color-text-primary); font-size: 10px; line-height: 1.5; }
</style>
