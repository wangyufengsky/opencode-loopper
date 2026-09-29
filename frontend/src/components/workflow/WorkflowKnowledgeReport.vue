<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import KnowledgeEvidence from '@/components/knowledge/KnowledgeEvidence.vue'
import { knowledgeToolLabel } from '@/utils/displayLabels'
import { knowledgeBody, knowledgeBundle } from './knowledgeBundle'
const props = defineProps<{ content: unknown }>()
const bundle = computed(() => knowledgeBundle(props.content)), selected = ref<number | null>(null)
const entry = computed(() => selected.value == null ? null : bundle.value?.entries[selected.value])
const body = computed(() => entry.value ? knowledgeBody(entry.value.content, knowledgeToolLabel(entry.value.toolName)) : null)
const title = (item: NonNullable<ReturnType<typeof knowledgeBundle>>['entries'][number]) => String(item.content.name || item.content.path || knowledgeToolLabel(item.toolName))
watch(() => props.content, () => { selected.value = null })
</script>
<template>
  <section v-if="bundle" class="workflow-knowledge-report" aria-label="交付的来源证据">
    <p>已选择 {{ bundle.entries.length }} 项资料随交付保存。</p>
    <p v-if="!bundle.entries.length">本次没有交付来源证据，请结合检索结论与局限判断下一步。</p>
    <ul v-if="bundle.limitations.length" class="limitations"><li v-for="(item, index) in bundle.limitations" :key="index">{{ item }}</li></ul>
    <p v-if="bundle.entries.length" class="note">检索结果与目录记录仅作线索，不能代替完整原文。</p>
    <ol><li v-for="(item, index) in bundle.entries" :key="item.reference"><button type="button" :aria-pressed="selected === index" @click="selected = selected === index ? null : index">{{ title(item) }}<small>{{ knowledgeToolLabel(item.toolName) }} · {{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small></button></li></ol>
    <KnowledgeEvidence v-if="body" :body="body" />
  </section>
  <p v-else>来源证据格式不完整，请查看原节点交付记录。</p>
</template>
<style scoped>
.workflow-knowledge-report { min-width: 0; overflow-wrap: anywhere; }
ol { padding-inline-start: 1.5rem; }
li { margin-block: .5rem; }
button { display: block; width: 100%; text-align: left; overflow-wrap: anywhere; white-space: normal; }
button[aria-pressed="true"] { border-color: var(--color-primary); }
small { display: block; }
.note, small { color: var(--color-text-secondary); font-size: .85rem; }
</style>
