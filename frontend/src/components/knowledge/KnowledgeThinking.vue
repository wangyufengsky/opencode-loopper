<script setup lang="ts">
import AssistantActivity from '@/components/AssistantActivity.vue'
import type { KnowledgeMessage } from '@/types/domain'
defineProps<{ message: KnowledgeMessage; thinking: string; answer: string }>()
</script>
<template><AssistantActivity :message="message" :thinking="thinking" :answer="answer" /></template>
