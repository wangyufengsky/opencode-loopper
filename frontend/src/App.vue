<script setup lang="ts">
import { onMounted } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { useTaskStore } from '@/stores/taskStore'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

const store = useTaskStore()
onMounted(() => store.loadOverview())
</script>

<template><el-config-provider :locale="zhCn"><a class="skip-link" href="#main-content">跳到主内容</a><div class="app-shell"><AppSidebar /><div class="app-main"><RouterView /></div></div></el-config-provider></template>
