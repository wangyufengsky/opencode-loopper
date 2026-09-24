<script setup lang="ts">
import { computed } from 'vue'
import type { RoleRevision } from '@/types/domain'
import { rolePrompt } from '@/utils/rolePrompt'
const props = defineProps<{ revision: RoleRevision }>()
const prompt = computed(() => rolePrompt(props.revision))
</script>

<template>
  <section class="prompt-document" aria-label="Prompt 全文">
    <header class="prompt-caption"><strong>Prompt 配置全文</strong><span>静态内容合并展示 · 按工作流选用</span></header>
    <dl v-if="prompt.variables.length" class="prompt-variables" aria-label="模板变量说明">
      <div v-for="variable in prompt.variables" :key="variable.name"><dt><code>{{ '{' + variable.name + '}' }}</code></dt><dd>{{ variable.description }}</dd></div>
    </dl>
    <pre v-if="prompt.text" class="prompt-body">{{ prompt.text }}</pre>
    <p v-else class="prompt-empty">此版本没有静态 Prompt 内容。</p>
  </section>
</template>

<style scoped>
.prompt-document{border:1px solid var(--color-border-default);border-radius:var(--radius-card);overflow:hidden;background:var(--color-bg-surface)}
.prompt-caption{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:16px 20px;background:var(--color-bg-elevated);border-bottom:1px solid var(--color-border-default);font-size:13px}
.prompt-caption span{font-size:12px;color:var(--color-text-secondary)}
.prompt-body{margin:0;padding:22px;font:13px/1.9 var(--font-code);white-space:pre-wrap;overflow-wrap:anywhere;color:var(--color-text-primary);tab-size:2}
.prompt-variables{margin:0;padding:16px 20px;border-bottom:1px solid var(--color-border-default);display:grid;gap:10px}.prompt-variables>div{display:flex;gap:14px;flex-wrap:wrap}.prompt-variables dt{color:var(--color-accent-cyan);font-size:12px}.prompt-variables dd{margin:0;font-size:12px;color:var(--color-text-secondary)}.prompt-empty{padding:20px;color:var(--color-text-secondary)}
</style>
