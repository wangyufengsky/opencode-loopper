<script setup lang="ts">
import StoryAccountingDialog from '@/components/StoryAccountingDialog.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template><el-config-provider :locale="zhCn"><a class="skip-link" href="#main-content">跳到主内容</a><div class="app-shell"><AppSidebar /><div class="app-main"><RouterView /></div></div><StoryAccountingDialog /></el-config-provider></template>
