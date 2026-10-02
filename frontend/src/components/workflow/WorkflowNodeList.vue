<script setup lang="ts">
import { computed, ref } from 'vue'
import type { WorkflowNode } from '@/types/domain'
import { workflowStateLabel } from '@/utils/displayLabels'

const props = defineProps<{ nodes: WorkflowNode[]; states?: Record<string, string> }>()
const emit = defineEmits<{ select: [id: string] }>()
const query = ref('')
const matches = computed(() => props.nodes.filter(node => node.title.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
</script>

<template>
  <div class="workflow-node-list">
    <input v-model="query" aria-label="搜索节点" placeholder="搜索节点…" />
    <button v-for="node in matches" :key="node.id" :aria-label="node.title" @click="emit('select', node.id)"><strong>{{ node.title }}</strong><span v-if="states">{{ workflowStateLabel(states[node.id] || 'PENDING') }}</span></button>
    <p v-if="!matches.length">没有匹配的节点。</p>
  </div>
</template>
