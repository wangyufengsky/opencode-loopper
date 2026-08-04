<script setup lang="ts">
import { onMounted } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { useTaskStore } from '@/stores/taskStore'

const store = useTaskStore()
onMounted(() => store.loadOverview())
</script>

<template><a class="skip-link" href="#main-content">跳到主内容</a><div class="app-shell"><AppSidebar /><div class="app-main"><RouterView /></div></div></template>
