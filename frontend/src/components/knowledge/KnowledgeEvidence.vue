<script setup lang="ts">
import { computed } from 'vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import type { KnowledgeContent, KnowledgeCitation } from '@/types/domain'
const props = defineProps<{ body: KnowledgeContent; citation?: KnowledgeCitation }>()
const language = computed(() => props.body.name?.endsWith('.java') ? 'java' : props.body.name?.endsWith('.json') ? 'json' : 'plain')
const database = computed(() => JSON.stringify(Object.fromEntries(Object.entries(props.body).filter(([key]) => !['sql', 'sourceId', 'sha256', 'kind', 'name', 'location'].includes(key))), null, 2))
</script>
<template>
  <section class="knowledge-evidence">
    <h3>{{ citation?.name || body.name }}</h3><p>{{ citation?.location || body.location }}</p>
    <p v-if="citation" class="knowledge-muted">采集于 {{ new Date(citation.createdAt).toLocaleString() }}</p>
    <p v-if="body.changeNotice" class="knowledge-notice">{{ body.changeNotice }}</p>
    <p v-if="body.sha256" class="knowledge-hash" :title="body.sha256">读取版本 {{ body.sha256.slice(0, 12) }}</p>
    <p v-for="limitation in body.limitations" :key="limitation" class="knowledge-notice">{{ limitation }}</p>
    <template v-if="body.kind === 'CODE'">
      <p class="knowledge-muted">原文件第 {{ body.startLine }}–{{ body.endLine }} 行</p>
      <CodeMergeEditor :model-value="body.text || ''" readonly :language="language" aria-label="引用代码片段" />
    </template>
    <template v-else-if="body.kind === 'DATABASE'">
      <p class="knowledge-notice">{{ body.truncated ? '结果已截断，仅展示采集范围' : '结果代表采集时刻' }}</p>
      <pre v-if="body.sql" class="knowledge-code">{{ body.sql }}</pre><CodeMergeEditor :model-value="database" readonly language="json" aria-label="数据库读取结果" />
    </template>
    <MarkdownDocument v-else :content="body.text || ''" />
  </section>
</template>
