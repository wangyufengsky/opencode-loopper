<script setup lang="ts">
import { Icon } from '@iconify/vue'
import { storeToRefs } from 'pinia'
import { useTaskStore } from '@/stores/taskStore'
import { statusLabel } from '@/utils/displayLabels'

const taskStore = useTaskStore()
const { runtime } = storeToRefs(taskStore)

const navigation = [
  { to: '/projects', icon: 'lucide:folder-kanban', label: '项目' },
  { to: '/designer', icon: 'lucide:sparkles', label: '设计与执行规范' },
  { to: '/designs', icon: 'lucide:history', label: '历史设计' },
  { to: '/tasks', icon: 'lucide:orbit', label: '任务' },
  { to: '/inbox', icon: 'lucide:inbox', label: '待处理中心' },
  { to: '/insights', icon: 'lucide:chart-no-axes-combined', label: '质量与用量' },
  { to: '/automations', icon: 'lucide:workflow', label: '模板与自动化' },
]
</script>

<template>
  <aside class="app-sidebar">
    <RouterLink class="brand" to="/tasks" aria-label="OpenCode Loopper 首页">
      <span class="brand-mark"><Icon icon="lucide:orbit" /></span>
      <span class="brand-copy">OpenCode Loopper<small>本地控制台</small></span>
    </RouterLink>

    <p class="nav-label">工作区</p>
    <nav aria-label="主导航">
      <RouterLink v-for="item in navigation" :key="item.to" class="nav-item" :to="item.to">
        <Icon :icon="item.icon" width="17" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <p class="nav-label">系统</p>
    <nav aria-label="系统导航">
      <RouterLink class="nav-item" to="/runtime"><Icon icon="lucide:cpu" width="17" /><span>运行环境</span></RouterLink>
      <RouterLink class="nav-item" to="/tools"><Icon icon="lucide:wrench" width="17" /><span>工具</span></RouterLink>
      <RouterLink class="nav-item" to="/settings"><Icon icon="lucide:settings-2" width="17" /><span>设置</span></RouterLink>
    </nav>

    <div class="sidebar-spacer" />
    <RouterLink class="runtime-mini" to="/runtime">
      <div class="runtime-mini-title"><span>OpenCode 运行环境</span><span :class="['status-badge', runtime?.status === 'ONLINE' ? 'status-success' : 'status-danger']">{{ runtime ? statusLabel(runtime.status) : '检查中' }}</span></div>
      <strong>{{ runtime?.model ?? '等待连接' }}</strong>
    </RouterLink>
  </aside>
</template>
